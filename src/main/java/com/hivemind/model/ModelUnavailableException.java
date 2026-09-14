package com.hivemind.model;

/**
 * 提供方临时不可用（限流 / 超时 / 5xx / 未配置）。
 * 语义是"可以换一家再试"，因此继承 RuntimeException 由路由器捕获后走降级链。
 *
 * <p>{@code retryable} 标记"同一提供方是否值得当场重试一次"：
 * 429/5xx/网络超时属于瞬时抖动，值得退避重试；4xx（鉴权/参数错误）重试无意义。
 */
public class ModelUnavailableException extends RuntimeException {

    private final boolean retryable;

    public ModelUnavailableException(String message) {
        this(message, true);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public ModelUnavailableException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ModelUnavailableException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** 是否值得在同一提供方上退避重试（瞬时错误为 true，确定性客户端错误为 false）。 */
    public boolean retryable() {
        return retryable;
    }
}
