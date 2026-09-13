package com.hivemind.model;

/**
 * 提供方临时不可用（限流 / 超时 / 5xx / 未配置）。
 * 语义是"可以换一家再试"，因此继承 RuntimeException 由路由器捕获后走降级链。
 */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
