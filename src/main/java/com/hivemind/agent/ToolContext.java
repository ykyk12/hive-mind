package com.hivemind.agent;

/** 工具调用上下文：让工具知道"谁在什么节点上执行了它"，也带审批结论。 */
public record ToolContext(String nodeId, String tenantId, String taskId, boolean approved) {

    public ToolContext withApproved(boolean approved) {
        return new ToolContext(nodeId, tenantId, taskId, approved);
    }
}
