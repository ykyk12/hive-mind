package com.hivemind.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由与降级的核心语义。
 * 这里用一个"指向 127.0.0.1:1 的假提供方"模拟不可用上游——不访问外网，连接被拒立即返回。
 */
class ModelRouterTest {

    private HiveProperties properties;

    @BeforeEach
    void setUp() {
        properties = new HiveProperties();
        properties.setModels(List.of(healthyMock(), brokenProvider(), codeMock()));
    }

    @Test
    void 权重更高但不可用时降级到可用的本地模型() {
        ModelRouter router = router();

        CompletionResponse response = router.execute(
                CompletionRequest.of(TaskType.CHAT, null, List.of(ChatMessage.user("你好"))));

        assertEquals("mock-local", response.providerId(), "坏提供方应被跳过，路由降级到本地模型");
        assertEquals(1, router.stats("broken-provider").failures(), "失败要被记账，供 EMA 学习");
        assertEquals(3, router.availableCount(), "三个提供方（两个本地模型 + 一个已配密钥的远端）都算可用：可用≠健康");
    }

    @Test
    void 连续失败达到阈值后断熔并标记为OPEN() {
        ModelRouter router = router();
        for (int i = 0; i < 3; i++) {
            router.execute(CompletionRequest.of(TaskType.CHAT, null, List.of(ChatMessage.user("试一次"))));
        }

        assertTrue(router.stats("broken-provider").failures() >= 3);
        assertEquals("OPEN", router.breaker("broken-provider").state());
        assertTrue(router.fallbackChain(TaskType.CHAT).size() >= 1, "断熔的提供方不参与降级链排序前置");
    }

    @Test
    void 能力画像决定排序_代码任务优先选择代码专长模型() {
        ModelRouter router = router();

        List<String> chain = router.fallbackChain(TaskType.CODE).stream()
                .map(provider -> provider.caps().id())
                .toList();

        // 权重更高的坏提供方仍会排在前面（画像+权重），但"有 CODE 强项"必须优于"没有该强项"
        assertTrue(chain.indexOf("code-mock") < chain.indexOf("mock-local"),
                "CODE 强项的模型应排在无该强项的模型之前：" + chain);
        assertTrue(router.score(TaskType.CODE, router.fallbackChain(TaskType.CODE).get(0)) > 0);
    }

    private ModelRouter router() {
        ProviderRegistry registry = new ProviderRegistry(properties, new ObjectMapper());
        return new ModelRouter(registry, properties);
    }

    private HiveProperties.ModelConfig healthyMock() {
        HiveProperties.ModelConfig config = new HiveProperties.ModelConfig();
        config.setId("mock-local");
        config.setProvider("mock");
        config.setModel("mock-local");
        config.setStrengths("CHAT,SUMMARIZE,EXTRACT");
        config.setWeight(10);
        config.setP50LatencyMs(5);
        return config;
    }

    private HiveProperties.ModelConfig codeMock() {
        HiveProperties.ModelConfig config = new HiveProperties.ModelConfig();
        config.setId("code-mock");
        config.setProvider("mock");
        config.setModel("code-mock");
        config.setStrengths("CODE,REASONING");
        config.setWeight(20);
        config.setP50LatencyMs(5);
        return config;
    }

    /** 刻意把权重设到最高：验证"权重高但挂了"时降级链仍然可用。 */
    private HiveProperties.ModelConfig brokenProvider() {
        HiveProperties.ModelConfig config = new HiveProperties.ModelConfig();
        config.setId("broken-provider");
        config.setProvider("openai-compatible");
        config.setModel("broken-model");
        config.setBaseUrl("http://127.0.0.1:1");
        config.setApiKey("test-key-not-real");
        config.setStrengths("CHAT,CODE");
        config.setWeight(100);
        config.setP50LatencyMs(100);
        return config;
    }
}
