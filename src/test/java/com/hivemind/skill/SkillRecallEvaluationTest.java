package com.hivemind.skill;

import com.hivemind.agent.SkillAdvisor;
import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召回质量评估集自检：评估集本身必须有效，否则它给出的"precision 100%"毫无意义。
 * 这里刻意在独立夹具上评估，不依赖运行期技能库。
 */
class SkillRecallEvaluationTest {

    private final HiveProperties properties = new HiveProperties();
    private final SkillRecallEvaluation evaluation =
            new SkillRecallEvaluation(new com.fasterxml.jackson.databind.ObjectMapper(), properties);

    @Test
    void 评估集夹具结构完整() {
        SkillRecallEvaluation.Fixture fixture = evaluation.fixture();

        assertTrue(fixture.skills().size() >= 5, "夹具技能太少，评估没有意义");
        assertTrue(fixture.cases().size() >= 10, "用例太少，评估没有意义");
        assertTrue(fixture.cases().stream().allMatch(c -> !c.expectAnyOf().isEmpty()), "每个用例都要有期望结果");
        assertTrue(fixture.cases().stream().allMatch(c -> fixture.skills().stream()
                        .anyMatch(s -> c.expectAnyOf().contains(s.skillId()))),
                "期望的技能必须都在夹具里，否则用例永远不可能命中");
    }

    @Test
    void 召回算法达到精度门槛() {
        SkillRecallEvaluation.Report report = evaluation.evaluate();

        assertTrue(report.precisionAtK() >= properties.getSkill().getRecallEvalMinPrecision(),
                "precision@" + report.topK() + " 未达标，未命中用例=" + report.missed());
        assertTrue(evaluation.passes());
        assertEquals(report.caseCount(), report.hits() + report.missed().size());
    }

    @Test
    void 每个用例的召回结果都可追溯到具体技能() {
        SkillRecallEvaluation.Report report = evaluation.evaluate(4);

        for (SkillRecallEvaluation.CaseResult result : report.details()) {
            assertTrue(result.recalled().size() <= 4, "topK 限制必须生效：" + result.recalled());
            if (result.hit()) {
                assertTrue(result.recalled().stream().anyMatch(result.expected()::contains),
                        "命中标记与召回结果必须一致：" + result);
            }
        }
        assertFalse(report.details().isEmpty());
    }

    @Test
    void 无关查询不应召回任何技能() {
        SkillStore isolated = new SkillStore(properties);
        isolated.publish(new SkillArtifact("skill-x", 0, "订单超时处理", "订单超时",
                "先查物流", java.util.Set.of("demo"), java.util.Set.of("n1"), "n1", System.currentTimeMillis()));

        List<SkillAdvisor.SkillHint> none = isolated.hintsFor("今天股市怎么样", 4);

        assertTrue(none.isEmpty(), "不相关查询不能注入经验");
    }
}
