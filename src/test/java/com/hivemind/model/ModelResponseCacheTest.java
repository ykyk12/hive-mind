package com.hivemind.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型响应幂等缓存：相同 prompt 命中缓存不重复打模型；TTL 过期失效；有界淘汰。
 */
class ModelResponseCacheTest {

    private final AtomicLong clock = new AtomicLong(10_000L);

    private ModelResponseCache cache() {
        return new ModelResponseCache(true, 60_000L, 4, clock::get);
    }

    @Test
    void 命中缓存不重复计算() {
        ModelResponseCache c = cache();
        CompletionRequest req = CompletionRequest.of(TaskType.CHAT, null, List.of(ChatMessage.user("你好")));
        String key = ModelResponseCache.key(req);
        assertFalse(c.get(key).isPresent());
        c.put(key, new CompletionResponse("p1", "m", "hi", 1, 1, 1L));
        var hit = c.get(key);
        assertTrue(hit.isPresent());
        assertEquals("hi", hit.get().text());
        assertEquals(1, c.hits());
        assertEquals(1, c.misses());
    }

    @Test
    void 不同消息不同键互不污染() {
        ModelResponseCache c = cache();
        CompletionRequest a = CompletionRequest.of(TaskType.CHAT, null, List.of(ChatMessage.user("问题甲")));
        CompletionRequest b = CompletionRequest.of(TaskType.CHAT, null, List.of(ChatMessage.user("问题乙")));
        c.put(ModelResponseCache.key(a), new CompletionResponse("p", "m", "A", 1, 1, 1L));
        assertTrue(c.get(ModelResponseCache.key(a)).isPresent());
        assertFalse(c.get(ModelResponseCache.key(b)).isPresent());
    }

    @Test
    void TTL过期后失效() {
        ModelResponseCache c = cache();
        String key = "k";
        c.put(key, new CompletionResponse("p", "m", "x", 1, 1, 1L));
        assertTrue(c.get(key).isPresent());
        clock.addAndGet(120_000L); // 越过 TTL 60s
        assertFalse(c.get(key).isPresent(), "过期条目必须被视为未命中并清除");
    }

    @Test
    void 超过上限淘汰最旧() {
        ModelResponseCache c = cache(); // maxEntries=4
        for (int i = 0; i < 6; i++) {
            c.put("k" + i, new CompletionResponse("p", "m", "v" + i, 1, 1, 1L));
        }
        assertTrue(c.size() <= 4, "有界缓存不能无限增长：" + c.size());
        assertFalse(c.get("k0").isPresent(), "最旧的 k0 应被淘汰");
        assertTrue(c.get("k5").isPresent(), "最新的 k5 应保留");
    }

    @Test
    void 关闭时不缓存不命中() {
        ModelResponseCache off = new ModelResponseCache(false, 60_000L, 4, clock::get);
        off.put("k", new CompletionResponse("p", "m", "x", 1, 1, 1L));
        assertFalse(off.enabled());
        assertFalse(off.get("k").isPresent());
    }

    @Test
    void 路由命中缓存不重复调用提供方() {
        HiveProperties properties = new HiveProperties();
        properties.getModel().getCache().setEnabled(true);
        properties.getModel().getCache().setTtlSeconds(60);
        properties.setModels(List.of(mockLocal()));
        ProviderRegistry registry = new ProviderRegistry(properties, new ObjectMapper());
        ModelRouter router = new ModelRouter(registry, properties);

        CompletionRequest req = CompletionRequest.of(TaskType.CHAT, null, List.of(ChatMessage.user("你好")));

        router.execute(req);
        long callsAfterFirst = router.stats("mock-local").calls();
        assertEquals(1, callsAfterFirst, "首次调用应真正打一次模型");

        router.execute(req);
        long callsAfterSecond = router.stats("mock-local").calls();
        assertEquals(1, callsAfterSecond, "相同 prompt 第二次应命中缓存，不再调用提供方");
        assertEquals(1L, ((Number) router.cacheStats().get("hits")).longValue(), "缓存命中应被记账");
        assertEquals(1L, ((Number) router.cacheStats().get("misses")).longValue(), "首次未命中应记一次 miss");
    }

    private HiveProperties.ModelConfig mockLocal() {
        HiveProperties.ModelConfig config = new HiveProperties.ModelConfig();
        config.setId("mock-local");
        config.setProvider("mock");
        config.setModel("mock-local");
        config.setStrengths("CHAT");
        config.setWeight(10);
        return config;
    }
}
