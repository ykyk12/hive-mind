package com.hivemind.skill;

import com.hivemind.agent.SkillAdvisor;
import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技能库：版本化、适应度、跨节点验证门槛、召回相关性。 */
class SkillStoreTest {

    private SkillStore store;

    @BeforeEach
    void setUp() {
        HiveProperties properties = new HiveProperties();
        properties.getSkill().setPromotionMinNodes(2);
        properties.getSkill().setPromotionMinFitness(0.6);
        properties.getSkill().setPromotionMinUses(3);
        store = new SkillStore(properties);
    }

    @Test
    void 版本递增且历史保留() {
        SkillArtifact first = store.publish(artifact("skill-a", 0, "订单超时处理", "先查物流再回复"));
        SkillArtifact second = store.publish(artifact("skill-a", 0, "订单超时处理 v2", "先查物流再回复并补偿"));

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals(2, store.versions("skill-a").size(), "历史版本必须保留，否则无法回滚");
        assertEquals(2, store.active("skill-a").orElseThrow().version());
    }

    @Test
    void 适应度随成功上升失败下降() {
        store.publish(artifact("skill-a", 0, "经验", "做法"));
        double initial = store.stats("skill-a").fitness();

        store.recordUsage(new SkillUsage("skill-a", 1, "node-a", "t1", true));
        double afterSuccess = store.stats("skill-a").fitness();
        store.recordUsage(new SkillUsage("skill-a", 1, "node-a", "t2", false));
        double afterFailure = store.stats("skill-a").fitness();

        assertTrue(afterSuccess > initial, "成功要抬高适应度");
        assertTrue(afterFailure < afterSuccess, "失败要压低适应度");
    }

    @Test
    void 只有跨节点验证过才允许推广() {
        store.publish(artifact("skill-a", 0, "订单超时处理", "先查物流"));
        for (int i = 0; i < 3; i++) {
            store.recordUsage(new SkillUsage("skill-a", 1, "node-a", "t" + i, true));
        }
        assertFalse(store.promotable("skill-a"), "只有一个节点验证过，不能推广（防单节点坏经验传染）");

        store.recordUsage(new SkillUsage("skill-a", 1, "node-b", "t4", true));

        assertTrue(store.promotable("skill-a"), "两个节点验证 + 使用次数 + 适应度都达标才可推广");
        assertEquals(1, store.promotableArtifacts().size());
    }

    @Test
    void 召回按相关度注入不相关经验不注入() {
        store.publish(artifact("skill-a", 0, "订单超时处理", "先查物流状态，再决定是否补偿"));

        List<SkillAdvisor.SkillHint> hit = store.hintsFor("订单超时怎么办", 3);
        List<SkillAdvisor.SkillHint> miss = store.hintsFor("今天天气怎么样", 3);

        assertEquals(1, hit.size(), "中文没有空格分词，这里验证字符级相似度召回有效");
        assertEquals("skill-a", hit.get(0).skillId());
        assertTrue(miss.isEmpty(), "不相关的经验绝不能注入上下文");
    }

    @Test
    void 接收同伴技能时补记验证节点() {
        store.publish(artifact("skill-a", 0, "经验", "做法"));

        store.upsertFromPeer(artifact("skill-a", 1, "经验", "做法"), "node-b");

        assertTrue(store.stats("skill-a").validators().contains("node-b"));
        assertEquals(1, store.versions("skill-a").size(), "已存在的版本不重复入库");
    }

    private SkillArtifact artifact(String skillId, int version, String title, String procedure) {
        return new SkillArtifact(skillId, version, title, title, procedure,
                Set.of("test"), Set.of("node-a"), "node-a", System.currentTimeMillis());
    }
}
