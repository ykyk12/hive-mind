package com.hivemind.change;

import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 提案仓库：提案、评审结论、门锁裁决、签名分开存——三权分立的数据结构基础。 */
@Component
public class ProposalStore {

    private final Map<String, ChangeProposal> proposals = new ConcurrentHashMap<>();
    private final Map<String, ReviewVerdict> verdicts = new ConcurrentHashMap<>();
    private final Map<String, GateDecision> decisions = new ConcurrentHashMap<>();
    private final Map<String, String> signatures = new ConcurrentHashMap<>();

    public ChangeProposal create(ChangeProposal proposal) {
        proposals.put(proposal.proposalId(), proposal);
        return proposal;
    }

    public ChangeProposal require(String proposalId) {
        ChangeProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "提案不存在：" + proposalId);
        }
        return proposal;
    }

    public ChangeProposal update(ChangeProposal proposal) {
        proposals.put(proposal.proposalId(), proposal);
        return proposal;
    }

    public void putVerdict(ReviewVerdict verdict) {
        verdicts.put(verdict.proposalId(), verdict);
    }

    public Optional<ReviewVerdict> verdict(String proposalId) {
        return Optional.ofNullable(verdicts.get(proposalId));
    }

    public void putDecision(GateDecision decision) {
        decisions.put(decision.proposalId(), decision);
    }

    public Optional<GateDecision> decision(String proposalId) {
        return Optional.ofNullable(decisions.get(proposalId));
    }

    public void sign(String proposalId, String signer) {
        signatures.put(proposalId, signer);
    }

    public Optional<String> signer(String proposalId) {
        return Optional.ofNullable(signatures.get(proposalId));
    }

    public List<ChangeProposal> all() {
        List<ChangeProposal> list = new ArrayList<>(proposals.values());
        list.sort(Comparator.comparingLong(ChangeProposal::createdAtMillis).reversed());
        return list;
    }

    public synchronized void clear() {
        proposals.clear();
        verdicts.clear();
        decisions.clear();
        signatures.clear();
    }
}
