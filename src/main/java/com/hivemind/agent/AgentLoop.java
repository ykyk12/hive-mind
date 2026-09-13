package com.hivemind.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.config.HiveProperties;
import com.hivemind.model.ChatMessage;
import com.hivemind.model.CompletionRequest;
import com.hivemind.model.CompletionResponse;
import com.hivemind.model.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 循环：模型 → 工具 → 观察 → 模型，直到模型给出最终答案或达到步数上限。
 *
 * 协议（模型必须输出 JSON）：
 *   调工具： {"action":"tool","tool":"echo","args":{"text":"hi"},"thought":"为什么调它"}
 *   给答案： {"action":"answer","answer":"...","thought":"..."}
 * 输出不是 JSON 时按"直接作答"处理——宁可降级，也不要因为模型不听话就整条链路失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private static final int OBSERVATION_MAX_CHARS = 2000;

    private final ModelRouter modelRouter;
    private final ToolRegistry toolRegistry;
    private final ConfirmationGate confirmationGate;
    private final SkillAdvisor skillAdvisor;
    private final ExperienceRecorder experienceRecorder;
    private final HiveProperties properties;
    private final ObjectMapper objectMapper;

    public AgentResult run(AgentTask task) {
        long start = System.nanoTime();
        String nodeId = properties.getNode().getId();
        int maxSteps = task.maxSteps() > 0 ? task.maxSteps() : properties.getAgent().getMaxSteps();

        List<SkillAdvisor.SkillHint> hints = task.useSkills()
                ? skillAdvisor.hintsFor(task.input(), properties.getAgent().getMaxInjectedSkills())
                : List.of();
        List<String> skillsUsed = hints.stream().map(h -> h.skillId() + "@" + h.version()).toList();

        CompletionRequest request = CompletionRequest.of(task.taskType(), systemPrompt(hints),
                List.of(ChatMessage.user(task.input())));

        List<AgentStep> steps = new ArrayList<>();
        List<String> toolsUsed = new ArrayList<>();
        ToolContext context = new ToolContext(nodeId, task.tenantId(), task.taskId(), task.approved());
        String answer = null;
        String modelId = null;
        String error = null;

        for (int index = 0; index < maxSteps; index++) {
            CompletionResponse response = modelRouter.execute(request);
            modelId = response.providerId();
            Map<String, Object> action = parseAction(response.text());
            String kind = String.valueOf(action.getOrDefault("action", "answer"));
            String thought = String.valueOf(action.getOrDefault("thought", ""));

            if ("tool".equals(kind)) {
                String toolName = String.valueOf(action.getOrDefault("tool", ""));
                Map<String, Object> args = toArgs(action.get("args"));
                Optional<Tool> found = toolRegistry.find(toolName);
                if (found.isEmpty()) {
                    steps.add(new AgentStep(index, thought, "tool", toolName, args, false,
                            "工具不存在：" + toolName, modelId));
                    request = request.append(ChatMessage.tool("FAILED: 工具不存在 " + toolName
                            + "，可选工具见系统提示词"));
                    continue;
                }
                Tool tool = found.get();
                ConfirmationGate.Decision decision = confirmationGate.check(task.taskId(), tool, context);
                if (!decision.allowed()) {
                    steps.add(new AgentStep(index, thought, "tool", toolName, args, false,
                            decision.reason(), modelId));
                    request = request.append(ChatMessage.tool("FAILED: " + decision.reason()));
                    continue;
                }
                ToolResult result = tool.invoke(context, args);
                toolsUsed.add(tool.name());
                steps.add(new AgentStep(index, thought, "tool", tool.name(), args, result.success(),
                        result.output(), modelId));
                request = request.append(ChatMessage.tool(result.forModel(OBSERVATION_MAX_CHARS)));
                continue;
            }

            answer = String.valueOf(action.getOrDefault("answer", response.text()));
            steps.add(new AgentStep(index, thought, "answer", null, Map.of(), true, answer, modelId));
            break;
        }

        if (answer == null) {
            error = "达到最大步数 " + maxSteps + " 仍未给出最终答案";
            answer = "（未完成）" + error;
        }

        long latency = (System.nanoTime() - start) / 1_000_000L;
        boolean success = error == null;
        AgentResult result = new AgentResult(task.taskId(), nodeId, answer, List.copyOf(steps), skillsUsed,
                modelId, latency, success, error);

        // 把轨迹交给记忆层：这是"神经元吸收经验"的唯一入口，失败轨迹也上报（负样本同样有价值）。
        // 影子/评测流量除外（task.reportExperience=false）：评测自身不能污染适应度统计。
        if (task.reportExperience()) {
            try {
                experienceRecorder.record(new TaskTrace(task.taskId(), task.tenantId(), nodeId, task.input(),
                        answer, List.copyOf(toolsUsed), skillsUsed, success, steps.size(), latency));
            } catch (RuntimeException e) {
                log.warn("经验上报失败（不影响任务结果）：{}", e.getMessage());
            }
        }
        return result;
    }

    private String systemPrompt(List<SkillAdvisor.SkillHint> hints) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 HiveMind 集群中的一个节点（").append(properties.getNode().getId()).append("）。\n")
                .append("你必须只输出一个 JSON 对象，不要输出任何解释文字或 Markdown 代码块。\n")
                .append("需要调用工具时：{\"action\":\"tool\",\"tool\":\"<工具名>\",\"args\":{...},\"thought\":\"<理由>\"}\n")
                .append("可以直接回答时：{\"action\":\"answer\",\"answer\":\"<答案>\",\"thought\":\"<理由>\"}\n")
                .append("规则：\n")
                .append("1) 能用工具拿事实就先用工具，不要臆造数据；\n")
                .append("2) 工具返回 FAILED 时，换参数或换工具，不要重复同一个失败调用；\n")
                .append("3) 中高风险工具若被拒绝，直接说明需要审批，不要绕过。\n")
                .append("可用工具：\n").append(toolRegistry.catalog());
        if (!hints.isEmpty()) {
            sb.append("参考经验（来自节点间的经验共享，可能不完全适用，判断后再用）：\n");
            for (SkillAdvisor.SkillHint hint : hints) {
                sb.append("- [").append(hint.skillId()).append("@").append(hint.version()).append("] ")
                        .append(hint.title()).append(" → ").append(hint.procedure())
                        .append("（适应度 ").append(String.format("%.2f", hint.fitness())).append("）\n");
            }
        }
        return sb.toString();
    }

    /** 宽松解析：容忍 Markdown 代码块与前后废话，取第一个 { 到最后一个 }。 */
    Map<String, Object> parseAction(String text) {
        String cleaned = text == null ? "" : text.trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of("action", "answer", "answer", cleaned, "thought", "模型输出非 JSON，按答案处理");
        }
        try {
            return objectMapper.readValue(cleaned.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return Map.of("action", "answer", "answer", cleaned, "thought", "JSON 解析失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toArgs(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> args = new LinkedHashMap<>();
            map.forEach((key, value) -> args.put(String.valueOf(key), value));
            return args;
        }
        return Map.of();
    }
}
