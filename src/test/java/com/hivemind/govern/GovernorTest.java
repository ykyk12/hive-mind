package com.hivemind.govern;

import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.change.GateDecision;
import com.hivemind.change.ProposalStatus;
import com.hivemind.change.ReviewVerdict;
import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 门锁规则：否决、升级人工、方向控制三条线。 */
class GovernorTest {

    private HiveProperties properties;

    @BeforeEach
    void setUp() {
        properties = new HiveProperties();
        properties.getEvolve().setAutoApplyKinds("SKILL,CONFIG,PLUGIN");
        HiveProperties.Governance governance = properties.getGovernance();
        governance.setKernelRequiresHuman(true);
        governance.setHumanSignatureRequired(false);
        governance.setMaxRiskScore(60);
        governance.setForbidSelfReview(true);
        governance.getDirection().setRequireBenefitStatement(true);
        governance.getDirection().setMaxCostIncreaseRatio(0.30);
        governance.getDirection().setMaxDeclaredRisk(70);
    }

    @Test
    void 合规插件自动放行() {
        GateDecision decision = new Governor(properties).evaluate(
                proposal(ChangeKind.PLUGIN, "proposer-agent", "减少一次模型往返", 20, Map.of()),
                verdict("reviewer-agent", true, 20));

        assertTrue(decision.allowed(), "原因=" + decision.reasons());
        assertFalse(decision.requiresHuman(), "PLUGIN 在自动放行清单内且风险分不高，不需要人工签名");
    }

    @Test
    void 内核变更必须人工签名() {
        GateDecision decision = new Governor(properties).evaluate(
                proposal(ChangeKind.KERNEL, "proposer-agent", "提升吞吐", 40, Map.of()),
                verdict("reviewer-agent", true, 30));

        assertTrue(decision.allowed(), "原因=" + decision.reasons());
        assertTrue(decision.requiresHuman(), "内核源码变更永不由门禁自动放行");
        assertTrue(decision.reasons().stream().anyMatch(r -> r.contains("内核")), decision.reasons().toString());
    }

    @Test
    void 缺审核结论直接否决() {
        GateDecision decision = new Governor(properties).evaluate(
                proposal(ChangeKind.PLUGIN, "proposer-agent", "收益", 20, Map.of()), null);

        assertFalse(decision.allowed());
        assertTrue(decision.reasons().stream().anyMatch(r -> r.contains("缺少审核结论")));
    }

    @Test
    void 未声明预期收益即否决() {
        GateDecision decision = new Governor(properties).evaluate(
                proposal(ChangeKind.PLUGIN, "proposer-agent", "", 20, Map.of()),
                verdict("reviewer-agent", true, 10));

        assertFalse(decision.allowed(), "没有方向声明的变更不许自动进入系统");
        assertTrue(decision.reasons().stream().anyMatch(r -> r.contains("未声明预期收益")));
    }

    @Test
    void 成本上升却无成功率证据视为反向进化() {
        GateDecision decision = new Governor(properties).evaluate(
                proposal(ChangeKind.PLUGIN, "proposer-agent", "换更贵的模型", 20,
                        Map.of("costIncreaseRatio", 0.8, "successGain", 0.0)),
                verdict("reviewer-agent", true, 20));

        assertFalse(decision.allowed());
        assertTrue(decision.reasons().stream().anyMatch(r -> r.contains("反方向进化")));
    }

    @Test
    void 审核者与提议者相同则否决() {
        GateDecision decision = new Governor(properties).evaluate(
                proposal(ChangeKind.PLUGIN, "same-agent", "收益", 20, Map.of()),
                verdict("same-agent", true, 20));

        assertFalse(decision.allowed());
        assertTrue(decision.reasons().stream().anyMatch(r -> r.contains("自审禁令")));
    }

    @Test
    void 风险分超阈值升级为人工签名() {
        GateDecision decision = new Governor(properties).evaluate(
                proposal(ChangeKind.PLUGIN, "proposer-agent", "收益", 20, Map.of()),
                verdict("reviewer-agent", true, 90));

        assertTrue(decision.allowed());
        assertTrue(decision.requiresHuman());
        assertEquals(90, decision.riskScore());
    }

    private ChangeProposal proposal(ChangeKind kind, String proposer, String benefit, int declaredRisk,
                                    Map<String, Object> extra) {
        return new ChangeProposal("p-1", kind, "变更", "理由", benefit, declaredRisk, proposer, extra,
                ProposalStatus.PROPOSED, System.currentTimeMillis());
    }

    private ReviewVerdict verdict(String reviewer, boolean approved, int risk) {
        return new ReviewVerdict("p-1", reviewer, approved, risk, approved ? List.of() : List.of("人为否决"),
                List.of(), "summary", "advisory", System.currentTimeMillis());
    }
}
