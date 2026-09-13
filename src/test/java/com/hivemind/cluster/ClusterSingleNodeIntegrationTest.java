package com.hivemind.cluster;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单节点集群的自举：启动后应当由心跳节拍自动当选大脑。
 * 这验证的是"同一份代码、无需人工指定角色"这一核心设计。
 */
@SpringBootTest(properties = {
        "hive.node.id=node-1",
        "hive.node.endpoint=http://127.0.0.1:8199",
        "hive.node.peers=",
        "hive.node.heartbeat-millis=100",
        "hive.node.lease-millis=300",
        "hive.evolve.root=target/hive-cluster-test"
})
class ClusterSingleNodeIntegrationTest {

    @Autowired
    private NodeRegistry registry;
    @Autowired
    private ElectionService election;

    @Test
    void 启动后自动当选大脑且无需外部干预() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !registry.self().isBrain()) {
            Thread.sleep(50);
        }

        assertTrue(registry.self().isBrain(), "单节点集群应在心跳节拍内自动当选大脑");
        assertEquals(NodeRole.BRAIN, registry.self().role());
        assertTrue(election.term() >= 1, "当选必须伴随 term 递增");
        assertTrue(election.leaseUntilMillis() > System.currentTimeMillis() - 300, "应当持有租约");
        assertNotNull(election.brainId());
    }

    @Test
    void 单节点集群的多数派就是一票() {
        assertEquals(1, registry.clusterSize());
        assertEquals(1, registry.quorum());
    }

    @Test
    void 未知同伴一律视为不存活() {
        assertTrue(registry.alivePeers().isEmpty());
        assertFalse(registry.brain().isPresent());
        assertEquals(0, registry.peers().size());
    }
}
