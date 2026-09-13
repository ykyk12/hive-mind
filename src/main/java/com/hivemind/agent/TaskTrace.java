package com.hivemind.agent;

/** 一次任务的完整轨迹：经验蒸馏的原料，也是"上报给大脑"的最小单位。 */
public record TaskTrace(String taskId,
                        String tenantId,
                        String nodeId,
                        String input,
                        String answer,
                        java.util.List<String> toolsUsed,
                        java.util.List<String> skillsUsed,
                        boolean success,
                        int steps,
                        long latencyMillis) {
}
