package com.hivemind.govern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.change.ProposalStatus;
import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.common.Ids;
import com.hivemind.model.CompletionRequest;
import com.hivemind.model.CompletionResponse;
import com.hivemind.model.MockModelProvider;
import com.hivemind.model.ModelRouter;
import com.hivemind.model.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提议者：把目标变成一份"可被审阅、可被否决"的提案。
 *
 * 职责边界很关键——提议者**不能**评审自己的提案，也不能决定是否放行；
 * 它只负责产出结构化变更 + 说明为什么值得做。这条边界由 Reviewer/Governor 强制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Proposer {

    public static final String AGENT_ID = "proposer-agent";

    private static final String SYSTEM = """
            你是自改提议者。给定一个能力缺口，产出一个最小可行的工具插件。
            只输出 JSON：{"className":"类名","packageName":"包名","source":"完整 Java 源码",
            "rationale":"为什么需要它","expectedBenefit":"预期收益（必须具体，例如减少一次外部模型往返）"}
            约束：包名必须是 com.hivemind.plugins 开头；源码只能 import java.* 与 com.hivemind.agent.*；
            禁止使用反射、进程、文件删除、系统退出；实现 com.hivemind.agent.Tool 接口。""";

    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;

    public ChangeProposal generate(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "goal 不能为空");
        }
        String prompt = MockModelProvider.GENERATE_PLUGIN_MARKER + goal
                + "\n请按上述 JSON 结构输出一个最小可行插件。";
        CompletionResponse response = modelRouter.execute(
                CompletionRequest.json(TaskType.CODE, SYSTEM, prompt));

        Map<String, Object> parsed = parse(response.text());
        String className = String.valueOf(parsed.getOrDefault("className", "")).trim();
        String packageName = String.valueOf(parsed.getOrDefault("packageName", "")).trim();
        String source = String.valueOf(parsed.getOrDefault("source", "")).trim();
        if (className.isBlank() || source.isBlank()) {
            throw new BizException(ErrorCode.GATE_REJECTED, "提议者输出无法解析为可编译插件（缺 className/source）");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("className", className);
        payload.put("packageName", packageName.isBlank() ? "com.hivemind.plugins.generated" : packageName);
        payload.put("source", source);

        ChangeProposal proposal = new ChangeProposal(Ids.shortId(), ChangeKind.PLUGIN,
                "新增工具插件：" + className,
                String.valueOf(parsed.getOrDefault("rationale", "满足目标：" + goal)),
                String.valueOf(parsed.getOrDefault("expectedBenefit", "")),
                30, AGENT_ID, payload, ProposalStatus.PROPOSED, System.currentTimeMillis());
        log.info("提议者产出提案 {}：{}（由模型 {} 生成）", proposal.proposalId(), proposal.title(),
                response.providerId());
        return proposal;
    }

    private Map<String, Object> parse(String text) {
        String cleaned = text == null ? "" : text.trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(cleaned.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("提议者输出解析失败：{}", e.getMessage());
            return Map.of();
        }
    }
}
