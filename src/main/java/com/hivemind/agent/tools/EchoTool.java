package com.hivemind.agent;

import java.util.Map;

/** 单个内置工具：最小可用示例，用来证明工具链路（目录→调用→观察回灌）是通的。 */
public final class EchoTool implements Tool {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "原样返回输入文本，用于验证工具链路";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.LOW;
    }

    @Override
    public Map<String, String> parameterSchema() {
        return Map.of("text", "要回显的文本");
    }

    @Override
    public ToolResult invoke(ToolContext context, Map<String, Object> args) {
        Object text = args.get("text");
        return ToolResult.ok(text == null ? "" : String.valueOf(text));
    }
}
