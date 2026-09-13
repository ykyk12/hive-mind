package com.hivemind.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地确定性模型：离线 / 单元测试 / 演示用，不依赖任何外部 API。
 *
 * 它同时是"协议样例"——Agent 循环要求模型输出 JSON 动作，人可以直接读这段代码
 * 看懂协议长什么样，而不必去猜某个云模型的行为。
 */
@Slf4j
public final class MockModelProvider implements ModelProvider {

    /** 触发工具调用的指令前缀，演示与测试都用它。 */
    public static final String CALL_TOOL_MARKER = "CALL_TOOL:";
    /** 触发技能蒸馏的指令前缀（由 ExperienceBus 使用）。 */
    public static final String DISTILL_MARKER = "DISTILL_SKILL:";
    /** 触发插件源码生成的指令前缀（由 Proposer 使用）。 */
    public static final String GENERATE_PLUGIN_MARKER = "GENERATE_PLUGIN:";

    private final ModelCaps caps;
    private final ObjectMapper objectMapper;

    public MockModelProvider(ModelCaps caps, ObjectMapper objectMapper) {
        this.caps = caps;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelCaps caps() {
        return caps;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        String user = request.lastUserText();
        String observation = lastToolObservation(request.messages());
        String text = route(user, observation);
        int completionTokens = Math.max(1, text.length() / 4);
        int promptTokens = Math.max(1, request.messages().stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum() / 4);
        return new CompletionResponse(caps.id(), caps.model(), text, promptTokens, completionTokens, 1L);
    }

    private String route(String user, String observation) {
        if (user.startsWith(DISTILL_MARKER)) {
            return json(distillSkill(user.substring(DISTILL_MARKER.length()).trim()));
        }
        if (user.startsWith(GENERATE_PLUGIN_MARKER)) {
            return json(generatePlugin(user.substring(GENERATE_PLUGIN_MARKER.length()).trim()));
        }
        if (observation != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "answer");
            payload.put("thought", "已拿到工具结果，据此作答");
            payload.put("answer", "根据工具返回：" + observation);
            return json(payload);
        }
        if (user.contains(CALL_TOOL_MARKER)) {
            String rest = user.substring(user.indexOf(CALL_TOOL_MARKER) + CALL_TOOL_MARKER.length()).trim();
            String toolName = rest.isEmpty() ? "echo" : rest.split("\\s+")[0];
            String argsText = rest.length() > toolName.length() ? rest.substring(toolName.length()).trim() : "";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "tool");
            payload.put("thought", "需要调用工具 " + toolName);
            payload.put("tool", toolName);
            payload.put("args", parseArgs(argsText));
            return json(payload);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "answer");
        payload.put("thought", "无需工具，直接回答");
        payload.put("answer", "（本地确定性模型）收到任务：" + user);
        return json(payload);
    }

    /** 技能蒸馏的确定性样例：真实环境由云模型产出，这里保证离线也能跑通全链路。 */
    private Map<String, Object> distillSkill(String hint) {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("title", "任务处理经验：" + shorten(hint, 40));
        skill.put("trigger", shorten(hint, 60));
        skill.put("procedure", "1) 识别输入中的关键实体；2) 选择最小必要工具；3) 用工具结果作答，不要臆造数据。");
        skill.put("tags", List.of("auto-distilled"));
        return skill;
    }

    /** 插件生成的确定性样例：产出一个可直接编译的 Tool 实现。 */
    private Map<String, Object> generatePlugin(String hint) {
        String className = "GeneratedTextStatsTool";
        String source = """
                package com.hivemind.plugins.generated;

                import com.hivemind.agent.RiskLevel;
                import com.hivemind.agent.Tool;
                import com.hivemind.agent.ToolContext;
                import com.hivemind.agent.ToolResult;

                import java.util.LinkedHashMap;
                import java.util.Map;

                /** 由进化流水线生成的插件：统计文本规模。仅依赖 java.* 与 agent 接口，便于隔离编译。 */
                public class %s implements Tool {

                    @Override
                    public String name() {
                        return "text_stats";
                    }

                    @Override
                    public String description() {
                        return "统计输入文本的字符数与词数";
                    }

                    @Override
                    public RiskLevel risk() {
                        return RiskLevel.LOW;
                    }

                    @Override
                    public Map<String, String> parameterSchema() {
                        Map<String, String> schema = new LinkedHashMap<>();
                        schema.put("text", "待统计的文本");
                        return schema;
                    }

                    @Override
                    public ToolResult invoke(ToolContext context, Map<String, Object> args) {
                        Object raw = args.get("text");
                        String text = raw == null ? "" : String.valueOf(raw);
                        int chars = text.length();
                        int words = text.isBlank() ? 0 : text.trim().split("\\\\s+").length;
                        return ToolResult.ok("字符数=" + chars + "，词数=" + words + "（任务 " + context.taskId() + "）");
                    }
                }
                """.formatted(className);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("className", className);
        payload.put("packageName", "com.hivemind.plugins.generated");
        payload.put("source", source);
        payload.put("rationale", "补一个零依赖的文本统计工具，覆盖：" + shorten(hint, 40));
        payload.put("expectedBenefit", "减少一次外部模型往返，同类任务成本下降");
        return payload;
    }

    private Map<String, Object> parseArgs(String argsText) {
        if (argsText == null || argsText.isBlank() || !argsText.startsWith("{")) {
            return Map.of("text", argsText == null ? "" : argsText);
        }
        try {
            return objectMapper.readValue(argsText, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.debug("本地模型的参数解析失败，回退为文本参数：{}", e.getMessage());
            return Map.of("text", argsText);
        }
    }

    private String lastToolObservation(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("tool".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return null;
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("本地模型序列化失败：{}", e.getMessage());
            return "{\"action\":\"answer\",\"answer\":\"本地模型序列化失败\"}";
        }
    }

    private String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
