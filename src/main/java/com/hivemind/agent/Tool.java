package com.hivemind.agent;

import java.util.Map;

/**
 * 工具契约。
 *
 * 刻意用 Map&lt;String,Object&gt; 而不是 JsonNode：自改流水线里模型生成的插件源码只需要
 * 依赖 java.util.Map 与这个接口，编译期依赖面越小，隔离编译越可靠。
 */
public interface Tool {

    /** 全局唯一名，模型用它来点名调用。 */
    String name();

    String description();

    RiskLevel risk();

    /** 参数说明：参数名 -> 用途（同时作为冒烟校验的入参清单）。 */
    Map<String, String> parameterSchema();

    ToolResult invoke(ToolContext context, Map<String, Object> args);

    /** 来源标记：builtin / plugin@版本，用于区分内置工具与进化产物。 */
    default String source() {
        return "builtin";
    }
}
