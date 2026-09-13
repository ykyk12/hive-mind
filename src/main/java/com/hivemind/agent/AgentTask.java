package com.hivemind.agent;

import com.hivemind.model.TaskType;

/** 一次 Agent 任务的内部表示。approve=true 表示调用方已带上高危工具审批令牌。 */
public record AgentTask(String taskId,
                        String tenantId,
                        String input,
                        TaskType taskType,
                        boolean approved,
                        int maxSteps) {

    public static AgentTask of(String taskId, String tenantId, String input, TaskType taskType,
                               boolean approved, int maxSteps) {
        return new AgentTask(taskId, tenantId == null ? "default" : tenantId, input,
                taskType == null ? TaskType.REASONING : taskType, approved, maxSteps);
    }
}
