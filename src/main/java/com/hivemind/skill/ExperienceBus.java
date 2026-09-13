package com.hivemind.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.agent.ExperienceRecorder;
import com.hivemind.agent.TaskTrace;
import com.hivemind.common.Ids;
import com.hivemind.config.HiveProperties;
import com.hivemind.model.CompletionRequest;
import com.hivemind.model.CompletionResponse;
import com.hivemind.model.MockModelProvider;
import com.hivemind.model.ModelRouter;
import com.hivemind.model.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 经验总线：神经元上报 → 统计 → 蒸馏成技能制品 → （跨节点验证后）推广。
 *
 * 刻意区别对待两类轨迹：
 *  - 成功且多步（用过工具）的轨迹才值得蒸馏——一步直答没有可复用经验；
 *  - 失败轨迹也统计（负样本同样有价值），但不蒸馏，避免把错误结论固化成"经验"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExperienceBus implements ExperienceRecorder {

    private static final String DISTILL_SYSTEM = """
            你是经验蒸馏器。把一次成功的任务处理过程抽象成可复用的操作技能。
            只输出 JSON：{"title":"简短标题","trigger":"什么样的输入该用这条经验","procedure":"分步骤的做法","tags":["标签"]}
            不要输出解释文字。""";

    private final SkillStore skillStore;
    private final ModelRouter modelRouter;
    private final HiveProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void record(TaskTrace trace) {
        for (String ref : trace.skillsUsed()) {
            String[] parts = ref.split("@");
            if (parts.length != 2) {
                continue;
            }
            try {
                skillStore.recordUsage(new SkillUsage(parts[0], Integer.parseInt(parts[1]),
                        trace.nodeId(), trace.taskId(), trace.success()));
            } catch (NumberFormatException e) {
                log.debug("技能引用格式不合法，已跳过：{}", ref);
            }
        }

        if (!properties.getSkill().isDistillEnabled()) {
            return;
        }
        if (!trace.success() || trace.steps() < 2) {
            log.debug("任务 {} 未达到蒸馏条件（success={} steps={}）", trace.taskId(), trace.success(), trace.steps());
            return;
        }
        distill(trace.input(), trace.answer(), trace.nodeId(), trace.taskId());
    }

    /** 显式蒸馏入口（也供 /api/v1/skills/distill 调用）。 */
    public Optional<SkillArtifact> distill(String input, String answer, String nodeId, String taskId) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        // 前缀是给路由用的机器可读标记（本地确定性模型据此产出样例），后面的提示词对人类与云模型都自解释
        String prompt = MockModelProvider.DISTILL_MARKER
                + "\n任务输入：" + input
                + "\n任务结果：" + (answer == null ? "" : answer)
                + "\n请按上述 JSON 结构输出这条可复用经验。";
        try {
            CompletionResponse response = modelRouter.execute(
                    CompletionRequest.json(TaskType.EXTRACT, DISTILL_SYSTEM, prompt));
            Optional<SkillArtifact> artifact = parse(response.text(), nodeId, taskId);
            artifact.ifPresent(a -> log.info("经验蒸馏成功：{} 来源任务 {} 节点 {}", a.ref(), taskId, nodeId));
            return artifact;
        } catch (RuntimeException e) {
            log.warn("经验蒸馏失败（不影响任务）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    Optional<SkillArtifact> parse(String modelText, String nodeId, String taskId) {
        String cleaned = modelText == null ? "" : modelText.trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(cleaned.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
            String title = str(raw.get("title"), "未命名经验");
            String trigger = str(raw.get("trigger"), "");
            String procedure = str(raw.get("procedure"), "");
            if (trigger.isBlank() || procedure.isBlank()) {
                return Optional.empty();
            }
            Set<String> tags = new LinkedHashSet<>();
            if (raw.get("tags") instanceof List<?> list) {
                list.forEach(item -> tags.add(String.valueOf(item)));
            }
            String skillId = "skill-" + Integer.toHexString((title + trigger).hashCode() & 0x7fffffff);
            if (duplicate(skillId, trigger)) {
                log.debug("蒸馏出的经验与已有技能重复，仅记统计不新增版本：{}", skillId);
                return skillStore.active(skillId);
            }
            return Optional.of(skillStore.publish(new SkillArtifact(skillId, 0, title, trigger, procedure,
                    Set.copyOf(tags), nodeId == null ? Set.of() : Set.of(nodeId), nodeId, System.currentTimeMillis())));
        } catch (Exception e) {
            log.warn("蒸馏结果解析失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean duplicate(String skillId, String trigger) {
        return skillStore.versions(skillId).stream()
                .anyMatch(existing -> existing.trigger().equalsIgnoreCase(trigger));
    }

    private String str(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw);
        return text.isBlank() ? fallback : text;
    }

    /** 手工构造一次经验（用于演示/测试，不经过模型）。 */
    public SkillArtifact recordManual(String title, String trigger, String procedure, String nodeId) {
        String skillId = "skill-" + Ids.shortId().substring(0, 6);
        List<String> tags = new ArrayList<>();
        tags.add("manual");
        return skillStore.publish(new SkillArtifact(skillId, 0, title, trigger, procedure,
                Set.copyOf(tags), nodeId == null ? Set.of() : Set.of(nodeId), nodeId, System.currentTimeMillis()));
    }
}
