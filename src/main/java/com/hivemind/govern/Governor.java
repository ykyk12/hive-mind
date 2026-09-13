package com.hivemind.govern;

import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.change.GateDecision;
import com.hivemind.change.ReviewVerdict;
import com.hivemind.common.Ids;
import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 门锁（Governor）：三权分立里握有"最终放行权"的一环，且是**纯确定性**的一环。
 *
 * 它做三件事：
 * 1) 否决——审核否决、缺审核、自审、没有收益声明、成本上涨无收益证据，一律不放行；
 * 2) 升级——不属于自动放行类型（尤其 KERNEL）、风险分超阈值、策略要求人工，则必须人工签名；
 * 3) 定向——用方向规则挡住"朝反方向进化"的变更（例如只涨成本不涨成功率）。
 * 这里没有任何模型调用：能让门锁松动的只有策略配置，而不是模型的一句话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Governor {

    public static final String AGENT_ID = "governor";

    private final HiveProperties properties;

    public GateDecision evaluate(ChangeProposal proposal, ReviewVerdict verdict) {
        List<String> reasons = new ArrayList<>();
        boolean allowed = true;
        boolean requiresHuman = false;
        HiveProperties.Governance governance = properties.getGovernance();

        if (verdict == null) {
            allowed = false;
            reasons.add("缺少审核结论：必须先由另一个角色审核，提议者不能自行裁决");
        } else {
            if (!verdict.approved()) {
                allowed = false;
                reasons.add("审核否决：" + verdict.blockers());
            }
            if (governance.isForbidSelfReview() && verdict.reviewer().equals(proposal.proposer())) {
                allowed = false;
                reasons.add("自审禁令：审核者 " + verdict.reviewer() + " 与提议者相同");
            }
        }

        int risk = verdict == null ? 100 : verdict.riskScore();

        Set<String> autoKinds = properties.getEvolve().autoApplyKindSet();
        if (!autoKinds.contains(proposal.kind().name())) {
            requiresHuman = true;
            reasons.add("变更类型 " + proposal.kind() + " 不在自动放行清单内（" + autoKinds + "）");
        }
        if (proposal.kind() == ChangeKind.KERNEL && governance.isKernelRequiresHuman()) {
            requiresHuman = true;
            reasons.add("内核源码变更：策略规定必须人工签名（kernel-requires-human=true）");
        }
        if (risk > governance.getMaxRiskScore()) {
            requiresHuman = true;
            reasons.add("风险分 " + risk + " 超过阈值 " + governance.getMaxRiskScore());
        }
        if (proposal.declaredRisk() > governance.getDirection().getMaxDeclaredRisk()) {
            requiresHuman = true;
            reasons.add("提议者自报风险 " + proposal.declaredRisk() + " 超过阈值 "
                    + governance.getDirection().getMaxDeclaredRisk());
        }

        // 方向控制：只放行"不违背目标向量"的变更
        if (governance.getDirection().isRequireBenefitStatement() && proposal.expectedBenefit().isBlank()) {
            allowed = false;
            reasons.add("未声明预期收益：无法判断这次变更是否朝目标前进（direction.require-benefit-statement=true）");
        }
        double costIncrease = proposal.payloadNumber("costIncreaseRatio", 0.0);
        double successGain = proposal.payloadNumber("successGain", 0.0);
        if (costIncrease > governance.getDirection().getMaxCostIncreaseRatio() && successGain <= 0.0) {
            allowed = false;
            reasons.add("声明成本上升 " + String.format("%.0f%%", costIncrease * 100)
                    + " 超过阈值 " + String.format("%.0f%%", governance.getDirection().getMaxCostIncreaseRatio() * 100)
                    + " 且没有成功率提升证据：属于朝反方向进化");
        }
        if (governance.isHumanSignatureRequired()) {
            requiresHuman = true;
            reasons.add("策略要求所有变更人工签名（human-signature-required=true）");
        }

        if (allowed && reasons.isEmpty()) {
            reasons.add("通过全部门锁规则");
        }
        GateDecision decision = new GateDecision(proposal.proposalId(), allowed, requiresHuman, risk,
                List.copyOf(reasons), Ids.shortId(), System.currentTimeMillis());
        log.info("门锁裁决 proposal={} allowed={} requiresHuman={} risk={} reasons={}",
                proposal.proposalId(), allowed, requiresHuman, risk, reasons);
        return decision;
    }
}
