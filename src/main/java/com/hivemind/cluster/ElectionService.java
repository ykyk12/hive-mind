package com.hivemind.cluster;

import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租约式选举（Raft 思路的轻量实现）：term 单调 + 多数派 quorum + 随机选举超时 + 大脑租约。
 *
 * 为什么非要 term 和 quorum：
 *  - 没有 term，两个节点会在网络抖动后互相认为对方过期，出现两个大脑同时指挥同一批神经元；
 *  - 没有 quorum，少数派（比如 3 节点里断联的 1 个）会自己当大脑，接受并执行"全局"变更；
 *  - 没有随机超时，多节点同时竞选会无限打平（活锁）。
 * 这三条不是理论洁癖，是"蜂巢"能不能自愈的分界线。
 */
@Slf4j
@Component
public class ElectionService {

    private final HiveProperties properties;
    private final NodeRegistry registry;
    private final PeerTransport transport;

    private final AtomicLong term = new AtomicLong(0L);
    private volatile long leaseUntilMillis;
    private volatile long lastBrainHeartbeatMillis;
    private volatile long electionDeadlineMillis;
    private volatile String brainId;

    public ElectionService(HiveProperties properties, NodeRegistry registry, PeerTransport transport) {
        this.properties = properties;
        this.registry = registry;
        this.transport = transport;
        this.lastBrainHeartbeatMillis = 0L;
        this.electionDeadlineMillis = System.currentTimeMillis() + randomDelay();
    }

    /** 定时节拍：大脑维持租约，神经元在超时后竞选。由 HeartbeatService 驱动。 */
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        if (registry.self().isBrain()) {
            if (now <= leaseUntilMillis) {
                return;
            }
            stepDown("租约到期（未获得多数派确认）");
        }
        if (now < electionDeadlineMillis) {
            return;
        }
        if (now - lastBrainHeartbeatMillis <= properties.getNode().getLeaseMillis()) {
            return;
        }
        campaign();
    }

    /** 竞选并尝试成为大脑。返回是否当选（单元测试直接调它，无需等待定时器）。 */
    public synchronized boolean campaign() {
        NodeDescriptor self = registry.self();
        int quorum = registry.quorum();
        long newTerm = term.incrementAndGet();
        int votes = 1;
        int peers = registry.peers().size();

        for (NodeDescriptor peer : registry.peers()) {
            PeerTransport.VoteResponse response = transport.requestVote(peer.endpoint(), self.nodeId(),
                    newTerm, self.weight());
            if (response.term() > newTerm) {
                long observed = response.term();
                term.set(observed);
                registry.updateSelf(descriptor -> descriptor.withTerm(observed));
            }
            if (response.granted()) {
                votes++;
            }
        }

        log.info("节点 {} 竞选 term={}：得票 {}/{}（多数派 {}）", self.nodeId(), newTerm, votes, peers + 1, quorum);
        if (votes >= quorum) {
            becomeBrain(newTerm);
            return true;
        }
        rearmElectionDeadline();
        return false;
    }

    public synchronized void becomeBrain(long newTerm) {
        registry.updateSelf(descriptor -> descriptor.withTerm(newTerm).withRole(NodeRole.BRAIN));
        leaseUntilMillis = System.currentTimeMillis() + properties.getNode().getLeaseMillis();
        brainId = registry.self().nodeId();
        log.warn("节点 {} 当选大脑（term={}，租约至 {}）", registry.self().nodeId(), newTerm, leaseUntilMillis);
    }

    public synchronized void stepDown(String reason) {
        NodeDescriptor self = registry.self();
        if (self.isBrain()) {
            log.warn("节点 {} 卸任大脑：{}", self.nodeId(), reason);
        }
        registry.updateSelf(descriptor -> descriptor.withRole(NodeRole.NEURON));
        leaseUntilMillis = 0L;
        brainId = null;
        rearmElectionDeadline();
    }

    /** 大脑收到神经元心跳后统计租约确认：只有拿到多数派确认才续租。 */
    public synchronized void recordLeaseAcks(int acknowledged, int quorum) {
        if (!registry.self().isBrain()) {
            return;
        }
        if (acknowledged + 1 >= quorum) {
            leaseUntilMillis = System.currentTimeMillis() + properties.getNode().getLeaseMillis();
            return;
        }
        log.warn("大脑 {} 只获得 {}/{} 个确认，租约不再续期（将在租约到期后卸任）",
                registry.self().nodeId(), acknowledged, quorum);
    }

    /** 处理拉票请求：term 更大者优先；已有有效大脑时不改选；权重更高者优先。 */
    public synchronized PeerTransport.VoteResponse handleVote(String candidateId, long candidateTerm,
                                                             int candidateWeight, String endpoint,
                                                             Set<String> capabilities) {
        long now = System.currentTimeMillis();
        if (endpoint != null && !endpoint.isBlank()) {
            registry.upsertPeer(new NodeDescriptor(candidateId, endpoint, capabilities, candidateWeight,
                    candidateTerm, NodeRole.NEURON, now));
        }
        if (candidateTerm < term.get()) {
            return new PeerTransport.VoteResponse(false, term.get());
        }
        if (candidateTerm > term.get()) {
            term.set(candidateTerm);
            long observed = candidateTerm;
            registry.updateSelf(descriptor -> descriptor.withTerm(observed));
            if (registry.self().isBrain()) {
                stepDown("观察到更高 term " + candidateTerm + " 的候选者 " + candidateId);
            }
        }
        if (registry.self().isBrain() && now <= leaseUntilMillis) {
            return new PeerTransport.VoteResponse(false, term.get());
        }
        if (candidateWeight < registry.self().weight()) {
            log.info("拒绝为 {} 投票：本节点权重 {} 更高", candidateId, registry.self().weight());
            return new PeerTransport.VoteResponse(false, term.get());
        }
        return new PeerTransport.VoteResponse(true, term.get());
    }

    /** 处理大脑心跳：更新 term、记录大脑、重置选举定时器；发现更高 term 则卸任。 */
    public synchronized PeerTransport.HeartbeatAck handleBrainHeartbeat(String incomingBrainId, long brainTerm,
                                                                       String endpoint, Set<String> capabilities,
                                                                       int weight) {
        long now = System.currentTimeMillis();
        if (endpoint != null && !endpoint.isBlank()) {
            registry.upsertPeer(new NodeDescriptor(incomingBrainId, endpoint, capabilities, weight, brainTerm,
                    NodeRole.BRAIN, now));
        }
        if (brainTerm < term.get()) {
            return new PeerTransport.HeartbeatAck(false, term.get(), registry.self().role().name());
        }
        if (registry.self().isBrain() && !registry.self().nodeId().equals(incomingBrainId)
                && brainTerm >= term.get()) {
            stepDown("发现 term=" + brainTerm + " 的新大脑 " + incomingBrainId);
        }
        term.set(Math.max(term.get(), brainTerm));
        lastBrainHeartbeatMillis = now;
        brainId = incomingBrainId;
        rearmElectionDeadline();
        return new PeerTransport.HeartbeatAck(true, term.get(), registry.self().role().name());
    }

    /** 神经元上报存活：大脑刷新对端信息。 */
    public synchronized PeerTransport.HeartbeatAck handleNeuronBeat(String nodeId, long nodeTerm, String endpoint,
                                                                   Set<String> capabilities, int weight) {
        registry.upsertPeer(new NodeDescriptor(nodeId, endpoint, capabilities, weight, nodeTerm,
                NodeRole.NEURON, System.currentTimeMillis()));
        return new PeerTransport.HeartbeatAck(true, term.get(), registry.self().role().name());
    }

    /** 收到投票/心跳里的更高 term 时同步，避免自己拿旧 term 反复竞选。 */
    public synchronized void observeTerm(long observed) {
        if (observed > term.get()) {
            term.set(observed);
            long value = observed;
            registry.updateSelf(descriptor -> descriptor.withTerm(value));
        }
    }

    public long term() {
        return term.get();
    }

    public long leaseUntilMillis() {
        return leaseUntilMillis;
    }

    public long lastBrainHeartbeatMillis() {
        return lastBrainHeartbeatMillis;
    }

    public String brainId() {
        return brainId;
    }

    public boolean isBrain() {
        return registry.self().isBrain();
    }

    private void rearmElectionDeadline() {
        electionDeadlineMillis = System.currentTimeMillis() + randomDelay();
    }

    /** 随机选举超时：租约 + [0, 租约) 抖动，避免多节点同时竞选打平。 */
    private long randomDelay() {
        long lease = properties.getNode().getLeaseMillis();
        return lease + ThreadLocalRandom.current().nextLong(lease + 1);
    }
}
