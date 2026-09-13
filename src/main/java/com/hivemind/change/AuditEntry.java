package com.hivemind.change;

/** 审计轨迹条目：append-only，任何一环的结论都能事后追责。 */
public record AuditEntry(String stage,
                         String proposalId,
                         String actor,
                         String detail,
                         long atMillis) {
}
