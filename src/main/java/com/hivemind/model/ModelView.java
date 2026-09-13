package com.hivemind.model;

import java.util.Set;

/** 模型编排状态的对外视图：永远不包含 apiKey。 */
public record ModelView(String id,
                        String provider,
                        String model,
                        String displayName,
                        boolean configured,
                        Set<TaskType> strengths,
                        double costPer1kIn,
                        double costPer1kOut,
                        long p50LatencyMs,
                        double successEma,
                        long calls,
                        long failures,
                        long latencyEmaMillis,
                        double chatScore,
                        String breaker) {
}
