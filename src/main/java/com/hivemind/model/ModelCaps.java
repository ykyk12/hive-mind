package com.hivemind.model;

import java.util.Set;

/**
 * 模型能力画像：路由依据是"画像 + 历史表现"，不是"哪个模型名气大"。
 * apiKey 刻意不放进画像——画像会通过 /api/v1/models 对外暴露，密钥不得进入可序列化对象。
 */
public record ModelCaps(String id,
                        String provider,
                        String model,
                        String displayName,
                        int contextWindow,
                        Set<TaskType> strengths,
                        double costPer1kIn,
                        double costPer1kOut,
                        long p50LatencyMs,
                        int weight,
                        boolean configured) {
}
