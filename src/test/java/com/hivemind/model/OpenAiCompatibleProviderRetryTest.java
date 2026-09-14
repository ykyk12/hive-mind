package com.hivemind.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提供方重试策略的纯函数判定（不发起真实网络请求）。
 */
class OpenAiCompatibleProviderRetryTest {

    @Test
    void retryableStatusCodesAreTransientOnly() {
        assertTrue(OpenAiCompatibleProvider.isRetryableStatus(429));
        assertTrue(OpenAiCompatibleProvider.isRetryableStatus(500));
        assertTrue(OpenAiCompatibleProvider.isRetryableStatus(502));
        assertTrue(OpenAiCompatibleProvider.isRetryableStatus(503));
        assertTrue(OpenAiCompatibleProvider.isRetryableStatus(504));
    }

    @Test
    void clientErrorsAreNotRetryable() {
        assertFalse(OpenAiCompatibleProvider.isRetryableStatus(400));
        assertFalse(OpenAiCompatibleProvider.isRetryableStatus(401));
        assertFalse(OpenAiCompatibleProvider.isRetryableStatus(403));
        assertFalse(OpenAiCompatibleProvider.isRetryableStatus(404));
    }

    @Test
    void exponentialBackoffGrowsAndCaps() {
        assertEquals(200, OpenAiCompatibleProvider.backoffMillis(0, 200));
        assertEquals(400, OpenAiCompatibleProvider.backoffMillis(1, 200));
        assertEquals(800, OpenAiCompatibleProvider.backoffMillis(2, 200));
        // 封顶 2s，避免第 N 次重试等待过久
        assertEquals(2000, OpenAiCompatibleProvider.backoffMillis(10, 200));
    }

    @Test
    void exceptionCarriesRetryableFlag() {
        assertTrue(new ModelUnavailableException("限流").retryable());
        assertFalse(new ModelUnavailableException("未配置 key", false).retryable());
    }
}
