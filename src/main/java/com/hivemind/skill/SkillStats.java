package com.hivemind.skill;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 技能在线表现：使用次数、成功率 EMA、验证过它的节点集合。
 *
 * 适应度先验 0.5（中性）：新技能既不该被当成灵丹，也不该被当成废纸。
 */
public final class SkillStats {

    private static final double ALPHA = 0.2;

    private final AtomicLong uses = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final Set<String> validators = ConcurrentHashMap.newKeySet();
    private volatile double fitness = 0.5;
    private volatile long lastUsedMillis;

    public void record(String nodeId, boolean success) {
        uses.incrementAndGet();
        if (success) {
            successes.incrementAndGet();
        }
        fitness = ALPHA * (success ? 1.0 : 0.0) + (1 - ALPHA) * fitness;
        lastUsedMillis = System.currentTimeMillis();
        if (nodeId != null && !nodeId.isBlank()) {
            validators.add(nodeId);
        }
    }

    /** 远端上报的验证也算数：这是"跨节点验证"的计数来源。 */
    public void recordValidation(String nodeId) {
        if (nodeId != null && !nodeId.isBlank()) {
            validators.add(nodeId);
        }
    }

    public long uses() {
        return uses.get();
    }

    public long successes() {
        return successes.get();
    }

    public double fitness() {
        return fitness;
    }

    public long lastUsedMillis() {
        return lastUsedMillis;
    }

    public Set<String> validators() {
        return Set.copyOf(validators);
    }
}
