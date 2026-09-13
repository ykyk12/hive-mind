package com.hivemind.cluster;

import java.util.Set;

/**
 * 节点间通信端口。抽成接口是为了让选举逻辑可以脱离网络做单元测试
 * （测试注入一个"全部同意投票"的假传输，就能验证 quorum 判定本身）。
 */
public interface PeerTransport {

    VoteResponse requestVote(String endpoint, String candidateId, long term, int weight);

    HeartbeatAck heartbeat(String endpoint, String brainId, long term, Set<String> capabilities,
                           int weight, String selfEndpoint);

    record VoteResponse(boolean granted, long term) {
    }

    record HeartbeatAck(boolean ok, long term, String role) {
    }
}
