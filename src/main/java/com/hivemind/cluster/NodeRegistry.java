package com.hivemind.cluster;

import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 节点注册表：自己 + 同伴（同伴通过配置的 peers 列表发现，再靠心跳/竞选消息补全信息）。
 *
 * 边界：内存实现，进程重启即重来；持久化与"跨网段自动发现"都在 README 的路线图里。
 */
@Slf4j
@Component
public class NodeRegistry {

    private final HiveProperties properties;
    private final Map<String, NodeDescriptor> peers = new ConcurrentHashMap<>();
    private volatile NodeDescriptor self;

    public NodeRegistry(HiveProperties properties) {
        this.properties = properties;
        initialize();
    }

    private void initialize() {
        HiveProperties.Node config = properties.getNode();
        this.self = new NodeDescriptor(config.getId(), config.getEndpoint(),
                java.util.Set.copyOf(config.capabilitySet()), config.getWeight(), 0L,
                NodeRole.NEURON, System.currentTimeMillis());

        // 同伴用 endpoint 占位注册，真正的能力/权重等信息等第一次心跳交换后补全
        for (String endpoint : config.peerList()) {
            String placeholderId = "peer@" + endpoint;
            peers.put(placeholderId, new NodeDescriptor(placeholderId, endpoint,
                    java.util.Set.of(), 0, 0L, NodeRole.NEURON, 0L));
        }
        log.info("节点 {} 启动：endpoint={} 能力={} 预置同伴 {} 个（角色=NEURON，等待竞选）",
                self.nodeId(), self.endpoint(), self.capabilities(), peers.size());
    }

    public NodeDescriptor self() {
        return self;
    }

    public synchronized NodeDescriptor updateSelf(UnaryOperator<NodeDescriptor> operator) {
        self = operator.apply(self);
        return self;
    }

    /** 收到心跳/竞选消息时补全对端信息。 */
    public synchronized void upsertPeer(NodeDescriptor descriptor) {
        if (descriptor == null || descriptor.nodeId() == null || descriptor.nodeId().equals(self.nodeId())) {
            return;
        }
        peers.entrySet().removeIf(entry -> entry.getKey().startsWith("peer@")
                && entry.getValue().endpoint().equals(descriptor.endpoint()));
        NodeDescriptor previous = peers.get(descriptor.nodeId());
        if (previous != null && previous.term() > descriptor.term()) {
            log.debug("忽略过期节点信息：{}（term {} < 已记录 {}）", descriptor.nodeId(), descriptor.term(), previous.term());
            return;
        }
        peers.put(descriptor.nodeId(), descriptor.touch());
    }

    public void markSeen(String nodeId) {
        NodeDescriptor descriptor = peers.get(nodeId);
        if (descriptor != null) {
            peers.put(nodeId, descriptor.touch());
        }
    }

    public List<NodeDescriptor> peers() {
        return peers.values().stream()
                .sorted(Comparator.comparing(NodeDescriptor::nodeId))
                .toList();
    }

    /** 存活 = 在租约期内有过消息。 */
    public List<NodeDescriptor> alivePeers() {
        long deadline = System.currentTimeMillis() - properties.getNode().getLeaseMillis();
        List<NodeDescriptor> alive = new ArrayList<>();
        for (NodeDescriptor descriptor : peers.values()) {
            if (descriptor.lastSeenMillis() >= deadline) {
                alive.add(descriptor);
            }
        }
        alive.sort(Comparator.comparing(NodeDescriptor::nodeId));
        return alive;
    }

    public Optional<NodeDescriptor> brain() {
        return peers.values().stream().filter(NodeDescriptor::isBrain)
                .max(Comparator.comparingLong(NodeDescriptor::term));
    }

    public int clusterSize() {
        return peers.size() + 1;
    }

    /** 多数派：2 节点集群需要 2 票，3 节点集群需要 2 票。 */
    public int quorum() {
        return clusterSize() / 2 + 1;
    }

    public Optional<NodeDescriptor> findPeer(String nodeId) {
        return Optional.ofNullable(peers.get(nodeId));
    }

    public synchronized void clearPeers() {
        peers.clear();
    }
}
