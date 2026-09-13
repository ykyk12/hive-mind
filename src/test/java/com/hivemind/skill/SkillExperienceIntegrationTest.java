package com.hivemind.skill;

import com.hivemind.agent.SkillAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2 经验面：影子对比、适应度淘汰、退役后不再召回。 */
@SpringBootTest(properties = {
        "hive.node.peers=",
        "hive.node.heartbeat-millis=1000",
        "hive.node.lease-millis=3000",
        "hive.evolve.root=target/hive-experience-test",
        "hive.skill.retire-min-uses=5",
        "hive.skill.retire-fitness-threshold=0.35"
})
class SkillExperienceIntegrationTest {

    @Autowired
    private SkillStore skillStore;
    @Autowired
    private SkillShadowService shadowService;
    @Autowired
    private SkillCurator skillCurator;

    @Test
    void 影子对比在注入组更优时更新适应度且不计入验证节点() {
        String skillId = "skill-shadow-" + System.nanoTime();
        skillStore.publish(artifact(skillId, "影子对比", "被注入时会体现在回答里"));
        double before = skillStore.stats(skillId).fitness();

        // 自定义判定：更看重"确实参考了注入经验"（默认判据不看这个信号）
        SkillShadowService.ShadowReport report = shadowService.compare("影子对比 请处理这个请求", skillId, null,
                result -> result.answer() != null && result.answer().contains("已参考经验") ? 2.0 : 1.0);

        assertEquals("SKILL_WINS", report.verdict(), "注入组得分更高时应判定技能胜出");
        assertTrue(report.fitnessUpdated());
        assertTrue(skillStore.stats(skillId).fitness() > before, "适应度必须上升");
        assertTrue(skillStore.stats(skillId).validators().isEmpty(),
                "影子对比是单节点自测，不能冒充跨节点验证，否则推广门槛会被自己刷过去");
    }

    @Test
    void 默认判据下本地模型倾向平局_不更新适应度() {
        String skillId = "skill-shadow-tie-" + System.nanoTime();
        skillStore.publish(artifact(skillId, "影子对比", "本地模型两组输出一致"));
        double before = skillStore.stats(skillId).fitness();

        SkillShadowService.ShadowReport report = shadowService.compare("影子对比 请求", skillId, null);

        assertEquals("TIE", report.verdict());
        assertFalse(report.fitnessUpdated());
        assertEquals(before, skillStore.stats(skillId).fitness(), 1e-9, "平局不应改动适应度");
        assertEquals(0, skillStore.stats(skillId).uses(), "平局不记使用次数");
    }

    @Test
    void 适应度掉到阈值以下的技能会被自动淘汰且不再召回() {
        String skillId = "skill-retire-" + System.nanoTime();
        skillStore.publish(artifact(skillId, "淘汰巡检", "这条经验会被判定为无效"));
        for (int i = 0; i < 5; i++) {
            skillStore.recordUsage(new SkillUsage(skillId, 1, "node-a", "t" + i, false));
        }
        assertTrue(skillStore.stats(skillId).fitness() < 0.35);

        assertTrue(skillStore.retireCandidates().contains(skillId), "用够了且适应度低，应进入淘汰候选");
        assertTrue(skillCurator.curateNow() >= 1);

        assertTrue(skillStore.isRetired(skillId));
        assertFalse(skillStore.promotable(skillId), "退役技能不允许推广");
        List<SkillAdvisor.SkillHint> hints = skillStore.hintsFor("淘汰巡检 相关请求", 4);
        assertTrue(hints.stream().noneMatch(hint -> hint.skillId().equals(skillId)),
                "退役技能不得再被召回：" + hints);
    }

    @Test
    void 人工退役可恢复且保留历史() {
        String skillId = "skill-manual-" + System.nanoTime();
        skillStore.publish(artifact(skillId, "人工退役", "过程说明"));

        skillStore.retire(skillId, "人工判定失效");
        assertTrue(skillStore.isRetired(skillId));
        assertFalse(skillStore.promotable(skillId));

        skillStore.unretire(skillId);
        assertFalse(skillStore.isRetired(skillId));
        assertEquals(1, skillStore.versions(skillId).size(), "退役不影响历史版本留存");
    }

    private SkillArtifact artifact(String skillId, String trigger, String procedure) {
        return new SkillArtifact(skillId, 0, trigger, trigger, procedure, Set.of("test"),
                Set.of("node-1"), "node-1", System.currentTimeMillis());
    }
}
