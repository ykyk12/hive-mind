package com.hivemind.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 提供方在线表现统计：成功率用指数滑动平均（EMA）。
 * 先验取 1.0（乐观）：新接入的模型不该因为"还没跑过"就被成本/延迟惩罚压到底部。
 */
public final class ModelStats {

    private static final double ALPHA = 0.2;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong latencyEmaMillis = new AtomicLong();
    private volatile double successEma = 1.0;

    public void recordSuccess(long latencyMillis) {
        calls.incrementAndGet();
        successEma = ALPHA + (1 - ALPHA) * successEma;
        latencyEmaMillis.updateAndGet(prev -> prev == 0
                ? latencyMillis
                : (long) (ALPHA * latencyMillis + (1 - ALPHA) * prev));
    }

    public void recordFailure() {
        calls.incrementAndGet();
        failures.incrementAndGet();
        successEma = (1 - ALPHA) * successEma;
    }

    public long calls() {
        return calls.get();
    }

    public long failures() {
        return failures.get();
    }

    public double successEma() {
        return successEma;
    }

    public long latencyEmaMillis() {
        return latencyEmaMillis.get();
    }
}
