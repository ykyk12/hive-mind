package com.hivemind.change;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 审计轨迹：append-only，内存实现。
 * 边界：进程重启会丢——生产应落库/落文件；当前形态是为了 demo 零依赖，README 已写明。
 */
@Slf4j
@Component
public class AuditTrail {

    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

    public void record(String stage, String proposalId, String actor, String detail) {
        AuditEntry entry = new AuditEntry(stage, proposalId, actor, detail, System.currentTimeMillis());
        entries.add(entry);
        log.info("[治理审计] stage={} proposal={} actor={} detail={}", stage, proposalId, actor, detail);
    }

    public List<AuditEntry> all() {
        return List.copyOf(entries);
    }

    public List<AuditEntry> forProposal(String proposalId) {
        return entries.stream().filter(e -> e.proposalId().equals(proposalId)).toList();
    }

    public void clear() {
        entries.clear();
    }
}
