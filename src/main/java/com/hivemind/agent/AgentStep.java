package com.hivemind.agent;

import java.util.Map;

/** 一步执行记录：模型想了什么、调了什么、拿到什么。可解释性的最小单元。 */
public record AgentStep(int index,
                        String thought,
                        String action,
                        String toolName,
                        Map<String, Object> args,
                        boolean toolSuccess,
                        String observation,
                        String modelId) {
}
