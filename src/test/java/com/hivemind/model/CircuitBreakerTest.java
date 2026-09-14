package com.hivemind.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断熔器状态机与半开单探针语义。
 */
class CircuitBreakerTest {

    @Test
    void closedAllowsRequests() {
        CircuitBreaker cb = new CircuitBreaker(3, 30_000);
        assertEquals("CLOSED", cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void opensAfterThresholdAndDeniesRequests() {
        CircuitBreaker cb = new CircuitBreaker(3, 30_000);
        for (int i = 0; i < 3; i++) {
            cb.onFailure();
        }
        assertEquals("OPEN", cb.state());
        assertFalse(cb.allowRequest(), "OPEN 期间应拒绝请求");
    }

    @Test
    void halfOpenAllowsOnlyOneProbe() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(3, 50);
        for (int i = 0; i < 3; i++) {
            cb.onFailure();
        }
        Thread.sleep(80); // 等冷却结束进入 HALF_OPEN
        assertEquals("HALF_OPEN", cb.state());
        assertTrue(cb.allowRequest(), "半开态应放行第一个探针");
        assertFalse(cb.allowRequest(), "半开态并发的第二个请求应被拒绝，避免打挂恢复中的提供方");
        // 探针成功 -> 回到 CLOSED
        cb.onSuccess();
        assertEquals("CLOSED", cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void probeFailureReopens() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(3, 50);
        for (int i = 0; i < 3; i++) {
            cb.onFailure();
        }
        Thread.sleep(80);
        assertTrue(cb.allowRequest());
        cb.onFailure(); // 半开探针失败 -> 重新熔断
        assertEquals("OPEN", cb.state());
    }
}
