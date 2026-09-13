package com.hivemind.cluster;

import java.util.Set;

/** 节点视图。term 单调不回退，是防"分裂脑"的关键字段。 */
public record NodeDescriptor(String nodeId,
                             String endpoint,
                             Set<String> capabilities,
                             int weight,
                             long term,
                             NodeRole role,
                             long lastSeenMillis) {

    public NodeDescriptor withRole(NodeRole nextRole) {
        return new NodeDescriptor(nodeId, endpoint, capabilities, weight, term, nextRole, System.currentTimeMillis());
    }

    public NodeDescriptor withTerm(long nextTerm) {
        return new NodeDescriptor(nodeId, endpoint, capabilities, weight, nextTerm, role, System.currentTimeMillis());
    }

    public NodeDescriptor touch() {
        return new NodeDescriptor(nodeId, endpoint, capabilities, weight, term, role, System.currentTimeMillis());
    }

    public boolean isBrain() {
        return role == NodeRole.BRAIN;
    }
}
