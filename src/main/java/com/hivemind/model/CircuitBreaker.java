package com.hivemind.model;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 断熔器：连续失败达阈值即 OPEN，拒绝后续请求一段时间（冷却期后放一个探针=HALF_OPEN）。
 * 目的是别让"已经挂掉的模型"继续吃掉每一次调用的超时预算。
 *
 * <p>HALF_OPEN 只放行"一个"并发探针：冷却结束后，多个并发请求不应全部涌向正在恢复的提供方，
 * 而是只有一个去试探，其余请求这一轮直接降级——借鉴经典断熔器单探针语义，
 * 避免恢复中的提供方被并发探测瞬间再次打挂。
 */
public final class CircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);
    private volatile long openUntilMillis;

    public CircuitBreaker(int failureThreshold, long openMillis) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    /** 是否放行本次请求（会在半开态占用唯一探针名额）。 */
    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        if (openUntilMillis == 0L) {
            return true; // CLOSED，从未熔断
        }
        if (now < openUntilMillis) {
            return false; // 仍在 OPEN
        }
        // HALF_OPEN：只有一个探针在飞；其余并发请求本轮降级
        return probeInFlight.compareAndSet(false, true);
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        openUntilMillis = 0;
        probeInFlight.set(false);
    }

    public void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilMillis = System.currentTimeMillis() + openMillis;
        }
        probeInFlight.set(false); // 探针已结束
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
