package com.hivemind.change;

import java.util.List;

/**
 * 门锁裁决。requiresHuman=true 表示"再往后必须有人签名"，不是失败——
 * 这是三权分立里"最终放行权不交给模型"的落点。
 */
public record GateDecision(String proposalId,
                           boolean allowed,
                           boolean requiresHuman,
                           int riskScore,
                           List<String> reasons,
                           String gateId,
                           long decidedAtMillis) {
}
