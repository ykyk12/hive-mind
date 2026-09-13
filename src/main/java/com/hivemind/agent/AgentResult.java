package com.hivemind.agent;

import java.util.List;

/** 任务结果。nodeId 让调用方知道"这次是谁干的"，集群里必须能追。 */
public record AgentResult(String taskId,
                          String nodeId,
                          String answer,
                          List<AgentStep> steps,
                          List<String> skillsUsed,
                          String modelId,
                          long latencyMillis,
                          boolean success,
                          String error) {
}
