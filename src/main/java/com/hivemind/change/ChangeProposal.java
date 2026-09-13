package com.hivemind.change;

import java.util.Map;

/**
 * 一次自改提案。
 *
 * 必须带 expectedBenefit（预期收益）：没有方向声明就没有适应度，
 * 没有适应度的"自动进化"只是随机漂移——所以收益声明是提案的必填项，门锁会检查它。
 */
public record ChangeProposal(String proposalId,
                             ChangeKind kind,
                             String title,
                             String rationale,
                             String expectedBenefit,
                             int declaredRisk,
                             String proposer,
                             Map<String, Object> payload,
                             ProposalStatus status,
                             long createdAtMillis) {

    public ChangeProposal withStatus(ProposalStatus next) {
        return new ChangeProposal(proposalId, kind, title, rationale, expectedBenefit, declaredRisk,
                proposer, payload, next, createdAtMillis);
    }

    public String payloadText(String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public double payloadNumber(String key, double fallback) {
        Object value = payload == null ? null : payload.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
