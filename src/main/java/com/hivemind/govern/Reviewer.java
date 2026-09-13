package com.hivemind.govern;

import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.change.ConfigWhitelist;
import com.hivemind.change.ReviewVerdict;
import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 审核者：对提案做"能不能跑、会不会伤到自己"的确定性检查。
 *
 * 刻意是规则驱动的（不是"再问一次模型"）：审核结论必须可复现、可解释、可争论。
 * 模型只能作为 advisory（参考意见）附加在结论里，不能决定通过与否——否则
 * "提议者用一次提示注入说服审核者"就成了绕过门禁的标准姿势。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Reviewer {

    public static final String AGENT_ID = "reviewer-agent";

    private static final Set<String> ALLOWED_IMPORT_PREFIXES = Set.of(
            "java.util.", "java.time.", "java.lang.", "com.hivemind.agent.");

    private final HiveProperties properties;

    public ReviewVerdict review(ChangeProposal proposal, String reviewerId) {
        String reviewer = reviewerId == null || reviewerId.isBlank() ? AGENT_ID : reviewerId;
        if (properties.getGovernance().isForbidSelfReview() && reviewer.equals(proposal.proposer())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "自审禁令：提案 " + proposal.proposalId() + " 的提议者是 " + proposal.proposer()
                            + "，不能由同一个人/同一个 Agent 审核——必须换审核者（如 " + AGENT_ID + "）");
        }

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int risk = 10;

        switch (proposal.kind()) {
            case PLUGIN -> risk += checkPlugin(proposal, blockers, warnings);
            case SKILL -> risk += checkSkill(proposal, blockers, warnings);
            case CONFIG -> risk += checkConfig(proposal, blockers, warnings);
            case KERNEL -> {
                risk += 45;
                warnings.add("内核变更：永不由门禁自动放行，必须人工签名");
                checkForbiddenPatterns(proposal.payloadText("patch"), blockers);
            }
            default -> blockers.add("未知变更类型：" + proposal.kind());
        }
        checkForbiddenPatterns(proposal.payloadText("source"), blockers);

        int declared = proposal.declaredRisk();
        if (declared > 60) {
            risk += 10;
            warnings.add("提议者自报风险 " + declared + " 偏高，需人工确认");
        }
        risk = Math.max(0, Math.min(risk, 100));

        String summary = blockers.isEmpty()
                ? "通过 " + blockers.size() + " 项阻断检查，风险分 " + risk
                : "发现 " + blockers.size() + " 项阻断问题，风险分 " + risk;
        ReviewVerdict verdict = new ReviewVerdict(proposal.proposalId(), reviewer, blockers.isEmpty(),
                risk, List.copyOf(blockers), List.copyOf(warnings), summary,
                "（顾问意见）本结论由规则静态分析产出，模型意见不参与判定", System.currentTimeMillis());
        log.info("审核完成 proposal={} reviewer={} approved={} risk={}",
                proposal.proposalId(), reviewer, verdict.approved(), risk);
        return verdict;
    }

    private int checkPlugin(ChangeProposal proposal, List<String> blockers, List<String> warnings) {
        String className = proposal.payloadText("className");
        String packageName = proposal.payloadText("packageName");
        String source = proposal.payloadText("source");
        String testClassName = proposal.payloadText("testClassName");
        String testSource = proposal.payloadText("testSource");
        if (className.isBlank() || source.isBlank()) {
            blockers.add("插件必须提供 className 与 source");
            return 15;
        }
        int lines = source.split("\n", -1).length;
        int limit = properties.getEvolve().getMaxPluginSourceLines();
        if (lines > limit) {
            blockers.add("源码 " + lines + " 行超过上限 " + limit + " 行：过大的变更不可审阅");
        }
        if (!packageName.startsWith("com.hivemind.plugins")) {
            blockers.add("包名必须以 com.hivemind.plugins 开头，当前为 " + packageName);
        }
        if (!source.contains("implements Tool")) {
            blockers.add("插件必须实现 com.hivemind.agent.Tool 接口");
        }
        if (!source.contains("public class " + className)) {
            blockers.add("源码中找不到 public class " + className);
        }
        checkImports(source, blockers);

        // 测试门禁：候选必须自带单元测试（否则运行时会被流水线拒绝，这里提前拦住，省一次编译）
        if (properties.getEvolve().isRequireTests()) {
            if (testClassName.isBlank() || testSource.isBlank()) {
                blockers.add("测试门禁要求提供 testClassName 与 testSource（候选插件必须自带单元测试）");
            }
        }
        if (!testSource.isBlank()) {
            int testLines = testSource.split("\n", -1).length;
            if (testLines > limit) {
                blockers.add("测试源码 " + testLines + " 行超过上限 " + limit + " 行");
            }
            if (testClassName.isBlank() || !testSource.contains("public class " + testClassName)) {
                blockers.add("测试源码中找不到 public class " + testClassName);
            }
            checkImports(testSource, blockers);
            checkForbiddenPatterns(testSource, blockers);
        }

        Set<String> riskyWords = new LinkedHashSet<>();
        for (String word : List.of("HttpClient", "Socket", "Thread", "ExecutorService")) {
            if (source.contains(word)) {
                riskyWords.add(word);
            }
        }
        if (!riskyWords.isEmpty()) {
            warnings.add("源码涉及并发/网络能力：" + riskyWords + "（在隔离环境执行需人工确认）");
            return 25;
        }
        return lines > limit * 0.6 ? 20 : 15;
    }

    /** import 白名单：隔离编译只提供 java.* 与 com.hivemind.agent.*（候选测试额外允许 JUnit）。 */
    private void checkImports(String source, List<String> blockers) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            String imported = trimmed.substring("import ".length()).replace(";", "").trim();
            if (imported.startsWith("static ")) {
                imported = imported.substring("static ".length()).trim();
            }
            boolean allowed = ALLOWED_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)
                    || imported.startsWith("org.junit.jupiter.")
                    || imported.startsWith("org.junit.platform.");
            if (!allowed) {
                blockers.add("禁止依赖外部包（隔离编译只提供 java.*、com.hivemind.agent.* 与 JUnit）：" + imported);
            }
        }
    }

    private int checkSkill(ChangeProposal proposal, List<String> blockers, List<String> warnings) {
        String trigger = proposal.payloadText("trigger");
        String procedure = proposal.payloadText("procedure");
        if (trigger.isBlank() || procedure.isBlank()) {
            blockers.add("技能必须提供 trigger 与 procedure");
        }
        if (trigger.length() > 300) {
            warnings.add("trigger 过长（" + trigger.length() + " 字符），召回会变差");
        }
        if (proposal.expectedBenefit().isBlank()) {
            warnings.add("技能类变更未声明收益，门锁会因此否决");
        }
        return 5;
    }

    private int checkConfig(ChangeProposal proposal, List<String> blockers, List<String> warnings) {
        String key = proposal.payloadText("key");
        if (!ConfigWhitelist.allowed(key)) {
            blockers.add("配置键不在热改白名单内：" + key + "（白名单=" + ConfigWhitelist.KEYS + "）");
        }
        String value = proposal.payloadText("value");
        if (value.isBlank()) {
            blockers.add("配置变更必须提供 value");
        }
        if (key.contains("lease") || key.contains("heartbeat")) {
            warnings.add("该键影响集群稳定性，改坏会导致节点间失联");
        }
        return 5;
    }

    private void checkForbiddenPatterns(String source, List<String> blockers) {
        if (source == null || source.isBlank()) {
            return;
        }
        for (String regex : properties.getGovernance().getForbiddenPatterns()) {
            try {
                if (Pattern.compile(regex).matcher(source).find()) {
                    blockers.add("命中禁止模式：" + regex);
                }
            } catch (RuntimeException e) {
                log.warn("禁止模式正则不合法，已跳过：{}", regex);
            }
        }
    }
}
