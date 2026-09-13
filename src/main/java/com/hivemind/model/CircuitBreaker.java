package com.hivemind.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 断熔器：连续失败达阈值即 OPEN，拒绝后续请求一段时间（冷却期后放一个探针=HALF_OPEN）。
 * 目的是别让"已经挂掉的模型"继续吃掉每一次调用的超时预算。
 */
public final class CircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntilMillis;

    public CircuitBreaker(int failureThreshold, long openMillis) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    /** 只读判断（不改变状态），供路由与展示使用。 */
    public boolean allowRequest() {
        return System.currentTimeMillis() >= openUntilMillis;
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        openUntilMillis = 0;
    }

    public void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilMillis = System.currentTimeMillis() + openMillis;
        }
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public String state() {
        if (openUntilMillis > System.currentTimeMillis()) {
            return "OPEN";
        }
        return consecutiveFailures.get() >= failureThreshold ? "HALF_OPEN" : "CLOSED";
    }
}
