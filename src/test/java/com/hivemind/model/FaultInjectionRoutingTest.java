package com.hivemind.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障注入：用一个可编程的假提供方（失败 N 次后成功）验证路由层的断熔与降级语义，
 * 不依赖网络，也不等真实冷却时间。
 */
class FaultInjectionRoutingTest {

    /** 会按预设次数抛错，之后固定成功；并记录被调用次数。 */
    private static final class FlakyProvider implements ModelProvider {
        private final ModelCaps caps;
        private final int failTimes;
        private final AtomicInteger calls = new AtomicInteger();

        FlakyProvider(String id, int failTimes) {
            this.caps = TestCaps.of(id, "CHAT,CODE");
            this.failTimes = failTimes;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public ModelCaps caps() {
            return caps;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            int n = calls.incrementAndGet();
            if (n <= failTimes) {
                throw new ModelUnavailableException("注入故障 #" + n, true);
            }
            return new CompletionResponse(caps.id(), caps.model(), "ok", 1, 1, 1L);
        }
    }

    @Test
    void 断熔后在OPEN期间不再触碰已熔断的提供方() {
        FlakyProvider flaky = new FlakyProvider("flaky", 3);
        // healthy 故意不给 CHAT 强项：这样 flaky 凭"CHAT 强项 +40"恒排降级链首位，
        // 否则一次失败后 EMA 会把它的排名挤下去，断熔器永远攒不够 3 次连续失败。
        ModelProvider healthy = new MockModelProvider(TestCaps.of("healthy", "REASONING"), new com.fasterxml.jackson.databind.ObjectMapper());
        ModelRouter router = TestRouters.with(flaky, healthy);
        CompletionRequest req = CompletionRequest.of(TaskType.CHAT, null, List.of(ChatMessage.user("hi")));

        // 前三次：flaky 先失败，路由降级到 healthy；第三次后 flaky 连续 3 次失败 -> 熔断 OPEN
        for (int i = 0; i < 3; i++) {
            router.execute(req);
        }
        assertEquals("OPEN", router.breaker("flaky").state());
        int callsBefore = flaky.calls();

        // 处于 OPEN：路由应直接跳过 flaky，不再打它
        router.execute(req);
        assertEquals(callsBefore, flaky.calls(), "OPEN 期间断熔器必须拦截，不能再把故障请求打给 flaky");
        assertEquals("healthy", router.execute(req).providerId());
    }

    @Test
    void 全部提供方都注入故障时抛业务异常且记账() {
        FlakyProvider dead = new FlakyProvider("dead", 99);
        ModelRouter router = TestRouters.with(dead);
        CompletionRequest req = CompletionRequest.of(TaskType.CHAT, null, List.of(ChatMessage.user("hi")));

        var ex = assertThrows(com.hivemind.common.BizException.class, () -> router.execute(req));
        assertTrue(ex.getMessage().contains("全部模型不可用") || ex.getMessage().contains("dead"), ex.getMessage());
        assertTrue(dead.calls() >= 1, "故障必须被记账");
    }

    /** 测试辅助：直接用提供方列表构造路由，绕过配置装配。 */
    private static final class TestRouters {
        static ModelRouter with(ModelProvider... providers) {
            com.hivemind.config.HiveProperties props = new com.hivemind.config.HiveProperties();
            props.getModel().getCache().setEnabled(false);
            ProviderRegistry registry = new ProviderRegistry(props, new com.fasterxml.jackson.databind.ObjectMapper()) {
                @Override
                public java.util.List<ModelProvider> all() {
                    return java.util.List.of(providers);
                }

                @Override
                public java.util.List<ModelProvider> available() {
                    return java.util.List.of(providers);
                }
            };
            return new ModelRouter(registry, props);
        }
    }

    /** 测试用最小 ModelCaps。 */
    private static final class TestCaps {
        static ModelCaps of(String id, String strengthsCsv) {
            java.util.Set<TaskType> strengths = new java.util.LinkedHashSet<>();
            for (String s : strengthsCsv.split(",")) {
                strengths.add(TaskType.valueOf(s.trim()));
            }
            return new ModelCaps(id, "openai-compatible", id, id, 8192, strengths,
                    0.0, 0.0, 5L, 10, true);
        }
    }
}
