package com.hivemind.agent;

import com.hivemind.model.TaskType;

/**
 * 一次 Agent 任务的内部表示。
 *
 * approve=true 表示调用方已带上高危工具审批令牌。
 * useSkills=false 用于影子对比的基线组（不注入任何经验）。
 * reportExperience=false 用于影子/评测流量：**评测不能污染适应度统计**，否则自测会把使用次数和验证节点刷上去。
 */
public record AgentTask(String taskId,
                        String tenantId,
                        String input,
                        TaskType taskType,
                        boolean approved,
                        int maxSteps,
                        boolean useSkills,
                        boolean reportExperience) {

    public static AgentTask of(String taskId, String tenantId, String input, TaskType taskType,
                               boolean approved, int maxSteps) {
        return new AgentTask(taskId, tenantId == null ? "default" : tenantId, input,
                taskType == null ? TaskType.REASONING : taskType, approved, maxSteps, true, true);
    }

    /** 基线组：完全不注入技能，用于 A/B 影子对比。 */
    public AgentTask withoutSkills() {
        return new AgentTask(taskId, tenantId, input, taskType, approved, maxSteps, false, reportExperience);
    }

    /** 影子/评测流量：跑完不把轨迹写回经验面。 */
    public AgentTask withoutReporting() {
        return new AgentTask(taskId, tenantId, input, taskType, approved, maxSteps, useSkills, false);
    }
}
