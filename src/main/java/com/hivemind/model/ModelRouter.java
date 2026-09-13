package com.hivemind.model;

import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型路由：把"用哪个模型"变成一个可解释的排序问题。
 *
 * 排序因子 = 能力匹配（该任务类型是否是它的强项）+ 历史成功率 EMA（在线学习）
 *          + 人工权重 - 成本 - 延迟。
 * 失败按降级链顺序依次尝试下一家；连续失败达阈值即断熔，避免每次调用都白等一次超时。
 */
@Slf4j
@Component
public class ModelRouter {

    private static final int BREAKER_FAILURE_THRESHOLD = 3;
    private static final long BREAKER_OPEN_MILLIS = 30_000L;

    private final ProviderRegistry registry;
    private final HiveProperties properties;
    private final Map<String, ModelStats> stats = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ModelRouter(ProviderRegistry registry, HiveProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /** 执行一次模型调用（含降级与断熔）。 */
    public CompletionResponse execute(CompletionRequest request) {
        TaskType type = request.taskType() == null ? TaskType.CHAT : request.taskType();
        List<ModelProvider> chain = fallbackChain(type);
        if (chain.isEmpty()) {
            throw new BizException(ErrorCode.NO_PROVIDER, "没有可用模型：检查 hive.models 配置与 API Key 环境变量");
        }
        List<String> tried = new ArrayList<>();
        RuntimeException last = null;
        for (ModelProvider provider : chain) {
            String id = provider.caps().id();
            CircuitBreaker breaker = breaker(id);
            if (!breaker.allowRequest()) {
                tried.add(id + "(断路)");
                continue;
            }
            tried.add(id);
            try {
                long start = System.nanoTime();
                CompletionResponse response = provider.complete(request);
                long latency = (System.nanoTime() - start) / 1_000_000L;
                stats(id).recordSuccess(latency);
                breaker.onSuccess();
                log.info("模型调用成功 provider={} task={} latency={}ms", id, type, latency);
                return response;
            } catch (RuntimeException e) {
                stats(id).recordFailure();
                breaker.onFailure();
                last = e;
                log.warn("模型调用失败，降级到下一家 provider={} reason={}", id, e.getMessage());
            }
        }
        throw new BizException(ErrorCode.NO_PROVIDER, "全部模型不可用，已尝试：" + tried, last);
    }

    /** 降级链：按分数降序；分数相同按 id 排序，保证行为可复现。 */
    public List<ModelProvider> fallbackChain(TaskType type) {
        List<ModelProvider> chain = new ArrayList<>(registry.available());
        chain.sort((a, b) -> {
            int byScore = Double.compare(score(type, b), score(type, a));
            return byScore != 0 ? byScore : a.caps().id().compareTo(b.caps().id());
        });
        return chain;
    }

    public double score(TaskType type, ModelProvider provider) {
        ModelCaps caps = provider.caps();
        double value = 0.0;
        if (caps.strengths().contains(type)) {
            value += 40.0;
        }
        value += stats(caps.id()).successEma() * 30.0;
        value += Math.min(caps.weight(), 30);
        value -= caps.costPer1kIn() * 1000.0;
        value -= Math.min(caps.p50LatencyMs() / 1000.0, 10.0);
        return value;
    }

    public ModelStats stats(String providerId) {
        return stats.computeIfAbsent(providerId, key -> new ModelStats());
    }

    public CircuitBreaker breaker(String providerId) {
        return breakers.computeIfAbsent(providerId,
                key -> new CircuitBreaker(BREAKER_FAILURE_THRESHOLD, BREAKER_OPEN_MILLIS));
    }

    /** 对外视图：报告"当前会选谁、为什么、各家状态如何"。 */
    public List<ModelView> views() {
        List<ModelView> views = new ArrayList<>();
        for (ModelProvider provider : registry.all()) {
            ModelCaps caps = provider.caps();
            ModelStats stat = stats(caps.id());
            views.add(new ModelView(caps.id(), caps.provider(), caps.model(), caps.displayName(),
                    caps.configured(), caps.strengths(), caps.costPer1kIn(), caps.costPer1kOut(),
                    caps.p50LatencyMs(), stat.successEma(), stat.calls(), stat.failures(),
                    stat.latencyEmaMillis(), score(TaskType.CHAT, provider), breaker(caps.id()).state()));
        }
        views.sort((a, b) -> Double.compare(b.chatScore(), a.chatScore()));
        return views;
    }

    /**
     * 同一问题并行问多家（"各取所长"的可视化）。
     * 注意：多数投票只在答案可判定对错时有效（代码能跑测试、事实可核对）；
     * 开放式问答投不出正确答案，这里只做并列展示，不做假装权威的合并。
     */
    public List<CompletionResponse> fanout(CompletionRequest request, int maxProviders) {
        List<CompletionResponse> responses = new ArrayList<>();
        int limit = Math.max(1, maxProviders);
        int used = 0;
        for (ModelProvider provider : fallbackChain(request.taskType())) {
            if (used >= limit) {
                break;
            }
            used++;
            try {
                responses.add(provider.complete(request));
            } catch (RuntimeException e) {
                stats(provider.caps().id()).recordFailure();
                log.warn("并行对比中 provider={} 失败：{}", provider.caps().id(), e.getMessage());
            }
        }
        return responses;
    }

    /** 供测试与诊断：当前配置下有多少家可用。 */
    public int availableCount() {
        return registry.available().size();
    }

    /** 让测试可以直接检查属性绑定（不额外暴露内部字段）。 */
    public HiveProperties properties() {
        return properties;
    }
}
