package com.hivemind.cluster;

import com.hivemind.config.HiveProperties;
import com.hivemind.skill.SkillStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 集群心跳节拍：大脑发心跳并统计租约确认、神经元上报存活并拉取已推广经验。
 *
 * 所有异常都在这里吞掉并降级（心跳挂了不能让节点自己崩）——但 term/租约逻辑仍由 ElectionService 严格把关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatService {

    private final HiveProperties properties;
    private final NodeRegistry registry;
    private final ElectionService election;
    private final PeerTransport transport;
    private final GossipService gossip;
    private final SkillStore skillStore;

    @Scheduled(fixedDelayString = "${hive.node.heartbeat-millis:1000}")
    public void beat() {
        try {
            election.tick();
            if (election.isBrain()) {
                heartbeatAsBrain();
            } else {
                reportAsNeuron();
            }
        } catch (RuntimeException e) {
            log.warn("心跳节拍异常（已忽略，下个周期重试）：{}", e.toString());
        }
    }

    private void heartbeatAsBrain() {
        NodeDescriptor self = registry.self();
        int acknowledged = 0;
        for (NodeDescriptor peer : registry.peers()) {
            PeerTransport.HeartbeatAck ack = transport.heartbeat(peer.endpoint(), self.nodeId(), self.term(),
                    self.capabilities(), self.weight(), self.endpoint());
            if (ack.ok()) {
                acknowledged++;
                if (ack.term() > self.term()) {
                    election.observeTerm(ack.term());
                    election.stepDown("同伴报告了更高的 term " + ack.term());
                    return;
                }
            }
        }
        election.recordLeaseAcks(acknowledged, registry.quorum());
        if (!registry.peers().isEmpty()) {
            log.debug("大脑 {} 心跳完成：确认 {}/{}", self.nodeId(), acknowledged, registry.peers().size());
        }
        gossip.pushToPeers();
    }

    private void reportAsNeuron() {
        NodeDescriptor self = registry.self();
        String target = election.brainId() == null
                ? registry.brain().map(NodeDescriptor::endpoint).orElse(null)
                : registry.findPeer(election.brainId()).map(NodeDescriptor::endpoint).orElse(null);
        if (target == null) {
            return;
        }
        transport.heartbeat(target, self.nodeId(), self.term(), self.capabilities(), self.weight(), self.endpoint());
        registry.markSeen(election.brainId() == null ? "" : election.brainId());
        int pulled = gossip.pullFrom(target);
        if (pulled > 0) {
            log.info("节点 {} 从大脑 {} 拉取到 {} 条已推广经验（当前技能库 {} 条）",
                    self.nodeId(), target, pulled, skillStore.size());
        }
    }

    /** 供 /api/v1/cluster/state 展示"我看见了谁"。 */
    public List<NodeDescriptor> alivePeers() {
        return registry.alivePeers();
    }

    public long heartbeatMillis() {
        return properties.getNode().getHeartbeatMillis();
    }
}
