package com.hivemind.common;

import org.slf4j.MDC;

/**
 * 统一响应体。
 *
 * 注意：record 组件的访问器必须 public，私有静态方法不能叫 traceId()——
 * 与组件同名会被编译器判定为"非法访问器"，所以这里用 currentTraceId()。
 */
public record ApiResponse<T>(boolean success, String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, ErrorCode.OK.name(), ErrorCode.OK.defaultMessage(), data, currentTraceId());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, ErrorCode.OK.name(), ErrorCode.OK.defaultMessage(), null, currentTraceId());
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message) {
        return new ApiResponse<>(false, code.name(),
                message == null ? code.defaultMessage() : message, null, currentTraceId());
    }

    /** 业务 traceId 之外，最外层 traceId 表示"本次请求的链路"，两者语义不同。 */
    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
