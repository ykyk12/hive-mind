package com.hivemind.cluster;

import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选举语义（用假传输，不联网）：多数派、权重优先、更高 term 让位。
 * 这些是"蜂巢能自愈"与"不出现两个大脑"的分界线，必须可测。
 */
class ClusterElectionTest {

    @Test
    void 单节点集群第一次竞选即当选() {
        ElectionService election = election("", true);

        assertTrue(election.campaign(), "单节点集群 quorum=1，自己投票即可当选");
        assertTrue(election.isBrain());
        assertEquals("node-1", election.brainId());
    }

    @Test
    void 三节点集群拿到多数派才当选() {
        ElectionService election = election("http://127.0.0.1:8102,http://127.0.0.1:8103", true);

        assertTrue(election.campaign());
        assertTrue(election.isBrain(), "自己 1 票 + 2 个同伴 2 票 ≥ quorum 2");
    }

    @Test
    void 拿不到多数派时不当选() {
        ElectionService election = election("http://127.0.0.1:8102,http://127.0.0.1:8103", false);

        assertFalse(election.campaign(), "少数派不应该自任大脑");
        assertFalse(election.isBrain());
    }

    @Test
    void 观察到更高term的大脑心跳即卸任() {
        ElectionService election = election("http://127.0.0.1:8102", true);
        election.campaign();
        assertTrue(election.isBrain());

        PeerTransport.HeartbeatAck ack = election.handleBrainHeartbeat("node-2", 7L,
                "http://127.0.0.1:8102", Set.of("chat"), 50);

        assertTrue(ack.ok());
        assertFalse(election.isBrain(), "同一任期不允许两个大脑");
        assertEquals(7L, election.term());
    }

    @Test
    void 权重更高的候选者才拿得到票() {
        ElectionService election = election("", true);
        // 本节点默认权重 100
        PeerTransport.VoteResponse lowWeight = election.handleVote("node-9", 0L, 10,
                "http://127.0.0.1:8199", Set.of("chat"));
        PeerTransport.VoteResponse highWeight = election.handleVote("node-8", 0L, 200,
                "http://127.0.0.1:8198", Set.of("chat"));

        assertFalse(lowWeight.granted(), "权重低者不应得到票");
        assertTrue(highWeight.granted(), "权重高者优先");
    }

    @Test
    void 租约确认不足时不再续租并最终卸任() {
        ElectionService election = election("http://127.0.0.1:8102,http://127.0.0.1:8103", true);
        election.campaign();
        long leaseBefore = election.leaseUntilMillis();

        election.recordLeaseAcks(0, 2);

        assertEquals(leaseBefore, election.leaseUntilMillis(), "没有多数派确认就不该延长租约");
    }

    private ElectionService election(String peers, boolean grantVotes) {
        HiveProperties properties = new HiveProperties();
        properties.getNode().setId("node-1");
        properties.getNode().setEndpoint("http://127.0.0.1:8101");
        properties.getNode().setPeers(peers);
        properties.getNode().setHeartbeatMillis(200);
        properties.getNode().setLeaseMillis(1000);
        NodeRegistry registry = new NodeRegistry(properties);
        return new ElectionService(properties, registry, new FakeTransport(grantVotes));
    }

    /** 假传输：可控的投票结果，用来隔离"网络"这一变量。 */
    private static final class FakeTransport implements PeerTransport {

        private final boolean grant;
        private final Set<String> voted = new HashSet<>();

        private FakeTransport(boolean grant) {
            this.grant = grant;
        }

        @Override
        public VoteResponse requestVote(String endpoint, String candidateId, long term, int weight) {
            voted.add(endpoint);
            return new VoteResponse(grant, term);
        }

        @Override
        public HeartbeatAck heartbeat(String endpoint, String brainId, long term, Set<String> capabilities,
                                      int weight, String selfEndpoint) {
            return new HeartbeatAck(true, term, "NEURON");
        }
    }
}
