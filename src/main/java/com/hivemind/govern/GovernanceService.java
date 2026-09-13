package com.hivemind.govern;

import com.hivemind.change.AuditEntry;
import com.hivemind.change.AuditTrail;
import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.change.ConfigWhitelist;
import com.hivemind.change.GateDecision;
import com.hivemind.change.ProposalStatus;
import com.hivemind.change.ProposalStore;
import com.hivemind.change.ReviewVerdict;
import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.common.Ids;
import com.hivemind.config.HiveProperties;
import com.hivemind.evolve.ApplyResult;
import com.hivemind.evolve.EvolutionPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 治理编排：提交 → 审核 → 裁决 → （人工签名）→ 应用。
 *
 * 三权分立的强制点就在这个类里：
 *  - 审核者不能是提议者（Reviewer 抛冲突）；
 *  - 签名人不能是提议者或审核者（这里抛冲突）；
 *  - 未裁决、未签名（当被要求时）一律不许应用。
 * 也就是说：**没有任何单一方能独自把变更推进到生效**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceService {

    private final ProposalStore store;
    private final AuditTrail audit;
    private final Proposer proposer;
    private final Reviewer reviewer;
    private final Governor governor;
    private final EvolutionPipeline pipeline;
    private final HiveProperties properties;

    public ChangeProposal submit(ChangeKind kind, String title, String rationale, String expectedBenefit,
                                 Integer declaredRisk, String proposerId, Map<String, Object> payload) {
        if (kind == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kind 不能为空");
        }
        String actor = proposerId == null || proposerId.isBlank() ? Proposer.AGENT_ID : proposerId;
        ChangeProposal proposal = new ChangeProposal(Ids.shortId(), kind,
                title == null || title.isBlank() ? kind + " 变更" : title,
                rationale == null ? "" : rationale,
                expectedBenefit == null ? "" : expectedBenefit,
                declaredRisk == null ? 20 : declaredRisk,
                actor, payload == null ? Map.of() : payload, ProposalStatus.PROPOSED, System.currentTimeMillis());
        store.create(proposal);
        audit.record("SUBMIT", proposal.proposalId(), actor, "类型=" + kind + " 标题=" + proposal.title());
        return proposal;
    }

    /** 由提议者 Agent 生成提案（走模型），再进同一条门禁流水线。 */
    public ChangeProposal generate(String goal, String proposerId) {
        ChangeProposal generated = proposer.generate(goal);
        if (proposerId != null && !proposerId.isBlank()) {
            generated = new ChangeProposal(generated.proposalId(), generated.kind(), generated.title(),
                    generated.rationale(), generated.expectedBenefit(), generated.declaredRisk(),
                    proposerId, generated.payload(), generated.status(), generated.createdAtMillis());
        }
        store.create(generated);
        audit.record("SUBMIT", generated.proposalId(), generated.proposer(),
                "由提议者生成，目标=" + goal);
        return generated;
    }

    public ReviewVerdict review(String proposalId, String reviewerId) {
        ChangeProposal proposal = store.require(proposalId);
        ReviewVerdict verdict = reviewer.review(proposal, reviewerId);
        store.putVerdict(verdict);
        store.update(proposal.withStatus(verdict.approved() ? ProposalStatus.REVIEWED : ProposalStatus.REJECTED));
        audit.record(verdict.approved() ? "REVIEW_PASSED" : "REVIEW_REJECTED", proposalId, verdict.reviewer(),
                verdict.summary() + (verdict.blockers().isEmpty() ? "" : " 阻断项=" + verdict.blockers()));
        return verdict;
    }

    public GateDecision gate(String proposalId) {
        ChangeProposal proposal = store.require(proposalId);
        ReviewVerdict verdict = store.verdict(proposalId).orElse(null);
        GateDecision decision = governor.evaluate(proposal, verdict);
        store.putDecision(decision);

        ProposalStatus status;
        if (!decision.allowed()) {
            status = ProposalStatus.REJECTED;
        } else if (decision.requiresHuman()) {
            status = ProposalStatus.AWAITING_SIGNATURE;
        } else {
            status = ProposalStatus.GATED;
        }
        store.update(proposal.withStatus(status));
        audit.record(decision.allowed() ? "GATE_PASSED" : "GATE_REJECTED", proposalId, Governor.AGENT_ID,
                "allowed=" + decision.allowed() + " requiresHuman=" + decision.requiresHuman()
                        + " reasons=" + decision.reasons());
        return decision;
    }

    /** 人工签名：签名者必须与提议者、审核者都不同（否则三权分立形同虚设）。 */
    public ChangeProposal sign(String proposalId, String signer, String note) {
        ChangeProposal proposal = store.require(proposalId);
        GateDecision decision = store.decision(proposalId)
                .orElseThrow(() -> new BizException(ErrorCode.CONFLICT, "提案尚未裁决，不能签名"));
        if (!decision.allowed()) {
            throw new BizException(ErrorCode.GATE_REJECTED, "提案已被门锁否决，签名无意义");
        }
        if (signer == null || signer.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "签名人不能为空");
        }
        if (signer.equals(proposal.proposer())) {
            throw new BizException(ErrorCode.CONFLICT, "签名人不能是提议者（" + proposal.proposer() + "）");
        }
        ReviewVerdict verdict = store.verdict(proposalId).orElse(null);
        if (verdict != null && signer.equals(verdict.reviewer())) {
            throw new BizException(ErrorCode.CONFLICT, "签名人不能是审核者（" + verdict.reviewer() + "）：审核与放行必须分权");
        }
        store.sign(proposalId, signer);
        ChangeProposal updated = store.update(proposal.withStatus(ProposalStatus.GATED));
        audit.record("SIGNED", proposalId, signer, note == null ? "人工签名放行" : note);
        return updated;
    }

    public ApplyResult apply(String proposalId) {
        ChangeProposal proposal = store.require(proposalId);
        GateDecision decision = store.decision(proposalId)
                .orElseThrow(() -> new BizException(ErrorCode.GATE_REJECTED, "提案尚未裁决：先走 /gate"));
        if (!decision.allowed()) {
            throw new BizException(ErrorCode.GATE_REJECTED, "门锁未放行：" + decision.reasons());
        }
        if (decision.requiresHuman() && store.signer(proposalId).isEmpty()) {
            throw new BizException(ErrorCode.GATE_REJECTED,
                    "该变更需要人工签名后才可应用（原因：" + decision.reasons() + "）");
        }
        ApplyResult result = pipeline.apply(proposal);
        store.update(proposal.withStatus(result.applied() ? ProposalStatus.APPLIED : ProposalStatus.FAILED));
        if (!result.applied()) {
            audit.record("APPLY_REJECTED", proposalId, "evolution-pipeline", result.message());
        }
        return result;
    }

    public Map<String, Object> detail(String proposalId) {
        ChangeProposal proposal = store.require(proposalId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposal", proposal);
        body.put("verdict", store.verdict(proposalId).orElse(null));
        body.put("decision", store.decision(proposalId).orElse(null));
        body.put("signer", store.signer(proposalId).orElse(null));
        body.put("audit", audit.forProposal(proposalId));
        return body;
    }

    public List<ChangeProposal> all() {
        return store.all();
    }

    public List<AuditEntry> audit(String proposalId) {
        return proposalId == null ? audit.all() : audit.forProposal(proposalId);
    }

    /** 当前生效的策略（门锁长什么样，必须让人一眼看到）。 */
    public Map<String, Object> policies() {
        HiveProperties.Governance governance = properties.getGovernance();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("autoApplyKinds", properties.getEvolve().autoApplyKindSet());
        body.put("kernelRequiresHuman", governance.isKernelRequiresHuman());
        body.put("humanSignatureRequired", governance.isHumanSignatureRequired());
        body.put("forbidSelfReview", governance.isForbidSelfReview());
        body.put("maxRiskScore", governance.getMaxRiskScore());
        body.put("maxPluginSourceLines", properties.getEvolve().getMaxPluginSourceLines());
        body.put("requireSmokeCheck", properties.getEvolve().isRequireSmokeCheck());
        body.put("hotConfigWhitelist", ConfigWhitelist.KEYS);
        body.put("forbiddenPatterns", governance.getForbiddenPatterns());
        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("requireBenefitStatement", governance.getDirection().isRequireBenefitStatement());
        direction.put("maxCostIncreaseRatio", governance.getDirection().getMaxCostIncreaseRatio());
        direction.put("maxDeclaredRisk", governance.getDirection().getMaxDeclaredRisk());
        body.put("direction", direction);
        return body;
    }
}
