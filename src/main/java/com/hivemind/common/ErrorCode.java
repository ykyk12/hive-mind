package com.hivemind.common;

/** 统一错误码。 */
public enum ErrorCode {
    OK("成功"),
    BAD_REQUEST("请求参数不合法"),
    NOT_FOUND("资源不存在"),
    UNAUTHORIZED("未授权"),
    CONFLICT("状态冲突"),
    QUOTA_EXCEEDED("配额超限"),
    NO_PROVIDER("没有可用的模型提供方"),
    TOOL_DENIED("工具调用被权限门拒绝"),
    GATE_REJECTED("变更被门禁拒绝"),
    INTERNAL_ERROR("服务内部错误");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
