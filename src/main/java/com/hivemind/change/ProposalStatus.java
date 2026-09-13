package com.hivemind.change;

/** 提案生命周期。PROPOSED →（评审）→（裁决）→（签名）→ APPLIED/FAILED，任一环节都可 REJECTED。 */
public enum ProposalStatus {
    PROPOSED,
    REJECTED,
    REVIEWED,
    AWAITING_SIGNATURE,
    GATED,
    APPLIED,
    FAILED
}
