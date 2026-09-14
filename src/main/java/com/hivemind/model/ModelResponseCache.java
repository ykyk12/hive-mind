package com.hivemind.model;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 模型响应幂等缓存：相同 prompt + 相同系统提示 + 相同任务类型，在 TTL 内直接返回上次成功结果。
 *
 * 设计取舍：
 *  - 只缓存"成功"响应：失败结果没有复用价值，且会污染断熔/学习统计；
 *  - 键是请求内容的 SHA-256，避免把大段 prompt 当 key 存进内存；
 *  - 命中不经过断熔与降级链：缓存命中意味着上次这条 prompt 已被验证可行，无需再打模型；
 *  - 有界：超过 maxEntries 按插入序淘汰最旧，防止内存无限增长；
 *  - 可注入时钟（LongSupplier），便于单测验证 TTL 过期，不依赖真实 sleep。
 */
@Slf4j
public final class ModelResponseCache {

    private final boolean enabled;
    private final long ttlMillis;
    private final int maxEntries;
    private final LongSupplier clock;

    /** key -> 条目；LinkedHashMap 记录插入序用于淘汰。整体加同一把锁保护。 */
    private final Map<String, Entry> map = new LinkedHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public ModelResponseCache(boolean enabled, long ttlMillis, int maxEntries, LongSupplier clock) {
        this.enabled = enabled;
        this.ttlMillis = Math.max(0, ttlMillis);
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 由请求内容派生稳定缓存键。 */
    public static String key(CompletionRequest request) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(request.taskType() == null ? "" : request.taskType().name()).append('\0');
        sb.append(request.systemPrompt() == null ? "" : request.systemPrompt()).append('\0');
        if (request.messages() != null) {
            for (ChatMessage m : request.messages()) {
                sb.append(m.role()).append(':').append(m.content() == null ? "" : m.content()).append('\0');
            }
        }
        return sha256(sb.toString());
    }

    public Optional<CompletionResponse> get(String key) {
        if (!enabled) {
            return Optional.empty();
        }
        synchronized (this) {
            Entry entry = map.get(key);
            if (entry == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            if (clock.getAsLong() >= entry.expireAt()) {
                map.remove(key);
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(entry.response());
        }
    }

    public void put(String key, CompletionResponse response) {
        if (!enabled || response == null) {
            return;
        }
        synchronized (this) {
            map.put(key, new Entry(response, clock.getAsLong() + ttlMillis));
            // 按插入序淘汰最旧
            while (map.size() > maxEntries) {
                Iterator<String> it = map.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public synchronized int size() {
        return map.size();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 一定可用；退化到字符串 hashCode 不至于让节点起不来
            return "h" + Integer.toHexString(input.hashCode());
        }
    }

    private record Entry(CompletionResponse response, long expireAt) {
    }
}
