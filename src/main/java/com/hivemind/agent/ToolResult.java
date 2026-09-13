package com.hivemind.agent;

/** 工具执行结果。output 会作为观察值回灌给模型，所以要控制长度、说清成败。 */
public record ToolResult(boolean success, String output) {

    public static ToolResult ok(String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult fail(String output) {
        return new ToolResult(false, output);
    }

    /** 回灌给模型的文本：超长截断，避免把上下文挤爆。 */
    public String forModel(int maxChars) {
        String prefix = success ? "OK: " : "FAILED: ";
        String body = output == null ? "" : output;
        if (body.length() > maxChars) {
            body = body.substring(0, maxChars) + "...(已截断)";
        }
        return prefix + body;
    }
}
