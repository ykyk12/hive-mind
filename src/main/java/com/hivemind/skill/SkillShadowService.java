package com.hivemind.skill;

import com.hivemind.agent.AgentLoop;
import com.hivemind.agent.AgentResult;
import com.hivemind.agent.AgentTask;
import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.common.Ids;
import com.hivemind.model.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 技能 A/B 影子对比：同一输入跑两遍——一遍注入技能，一遍不注入（基线）——比较结果并回写适应度。
 *
 * 两个必须说清楚的边界：
 * 1) 判定函数是**代理指标**（可替换的策略），不是真值。默认判据是"成功 + 引用了工具观察 + 步数少 + 延迟低"；
 * 2) 影子对比只更新适应度、**不增加"验证节点"计数**：同一个节点自己测自己，不能冒充跨节点验证，
 *    否则"≥2 个节点验证才推广"的门槛会被自己刷过去。
 *
 * 已知局限：用本地确定性模型时两组输出完全相同，必然平局——影子对比要有区分度，需要接入真实模型。
 * 这一点写在 docs/ARCHITECTURE.md，不靠调参掩盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillShadowService {

    /** 判定策略：给出一个变体的得分，得分高者胜。可替换，便于用真实模型或人工评分接入。 */
    @FunctionalInterface
    public interface ShadowJudge {
        double score(AgentResult result);
    }

    /** 默认判据（代理指标）：成功 +2；引用了工具观察 +1；步数 ≤2 +0.2；延迟 <50ms +0.3。 */
    public static final ShadowJudge DEFAULT_JUDGE = result -> {
        double score = result.success() ? 2.0 : 0.0;
        String answer = result.answer() == null ? "" : result.answer();
        if (answer.contains("工具返回") || answer.contains("OK:")) {
            score += 1.0;
        }
        if (result.steps().size() <= 2 && !result.steps().isEmpty()) {
            score += 0.2;
        }
        if (result.latencyMillis() < 50) {
            score += 0.3;
        }
        return score;
    };

    private final AgentLoop agentLoop;
    private final SkillStore skillStore;

    public record VariantOutcome(String variant, boolean success, String answer, int steps,
                                 long latencyMillis, double score) {
    }

    public record ShadowReport(String input,
                               String skillRef,
                               VariantOutcome withSkill,
                               VariantOutcome baseline,
                               String verdict,
                               boolean fitnessUpdated,
                               String note) {
    }

    public ShadowReport compare(String input, String skillId, Integer version) {
        return compare(input, skillId, version, DEFAULT_JUDGE);
    }

    public ShadowReport compare(String input, String skillId, Integer version, ShadowJudge judge) {
        if (input == null || input.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "input 不能为空");
        }
        SkillArtifact artifact = resolve(skillId, version);
        if (skillStore.isRetired(skillId)) {
            throw new BizException(ErrorCode.CONFLICT, "技能 " + skillId + " 已退役，不再做影子对比");
        }

        VariantOutcome baseline = run(input, false, "baseline", judge);
        VariantOutcome withSkill = run(input, true, artifact.ref(), judge);

        String verdict;
        if (withSkill.score() > baseline.score()) {
            verdict = "SKILL_WINS";
        } else if (withSkill.score() < baseline.score()) {
            verdict = "SKILL_LOSES";
        } else {
            verdict = "TIE";
        }

        boolean fitnessUpdated = false;
        if (!"TIE".equals(verdict)) {
            // nodeId 传 null：只更新适应度，不记入"验证节点"，避免自测抬高推广门槛
            skillStore.recordUsage(new SkillUsage(skillId, artifact.version(), null,
                    "shadow-" + Ids.shortId(), "SKILL_WINS".equals(verdict)));
            fitnessUpdated = true;
        }
        String note = fitnessUpdated
                ? "已按影子结果更新适应度（未计入验证节点）"
                : "平局：适应度不变（本地确定性模型下两组输出一致，属预期）";
        log.info("影子对比 {} verdict={} withSkill={} baseline={} 适应度更新={}",
                artifact.ref(), verdict, withSkill.score(), baseline.score(), fitnessUpdated);
        return new ShadowReport(input, artifact.ref(), withSkill, baseline, verdict, fitnessUpdated, note);
    }

    private VariantOutcome run(String input, boolean useSkills, String variant, ShadowJudge judge) {
        AgentTask task = AgentTask.of("shadow-" + Ids.shortId(), "shadow", input, TaskType.REASONING, false, 4)
                .withoutReporting();
        AgentResult result = agentLoop.run(useSkills ? task : task.withoutSkills());
        return new VariantOutcome(variant, result.success(), result.answer(), result.steps().size(),
                result.latencyMillis(), judge.score(result));
    }

    private SkillArtifact resolve(String skillId, Integer version) {
        if (skillId == null || skillId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "skillId 不能为空");
        }
        if (version == null || version <= 0) {
            return skillStore.active(skillId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "技能不存在：" + skillId));
        }
        return skillStore.versions(skillId).stream()
                .filter(artifact -> artifact.version() == version)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "技能 " + skillId + " 没有版本 " + version));
    }
}
