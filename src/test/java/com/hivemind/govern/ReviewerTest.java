package com.hivemind.govern;

import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.change.ProposalStatus;
import com.hivemind.change.ReviewVerdict;
import com.hivemind.common.BizException;
import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审核者的确定性检查：该拒的必须拒，且要指出原因。 */
class ReviewerTest {

    private HiveProperties properties;

    @BeforeEach
    void setUp() {
        properties = new HiveProperties();
        properties.getGovernance().setForbiddenPatterns(List.of(
                "System\\s*\\.\\s*exit",
                "Runtime\\s*\\.\\s*getRuntime",
                "java\\.lang\\.reflect"));
        properties.getGovernance().setForbidSelfReview(true);
        properties.getEvolve().setMaxPluginSourceLines(60);
    }

    @Test
    void 自审被拒() {
        ChangeProposal proposal = pluginProposal(Proposer.AGENT_ID, validPluginSource("SmokeTool", "return ToolResult.ok(\"ok\");"));

        BizException error = assertThrows(BizException.class,
                () -> new Reviewer(properties).review(proposal, Proposer.AGENT_ID));

        assertTrue(error.getMessage().contains("自审禁令"), "应明确拒绝自己审自己：" + error.getMessage());
    }

    @Test
    void 合规插件通过审核() {
        ChangeProposal proposal = pluginProposal("someone", validPluginSource("SmokeTool", "return ToolResult.ok(\"ok\");"));

        ReviewVerdict verdict = new Reviewer(properties).review(proposal, Reviewer.AGENT_ID);

        assertTrue(verdict.approved(), "阻断项=" + verdict.blockers());
        assertTrue(verdict.riskScore() <= 60);
    }

    @Test
    void 命中禁止模式即否决() {
        ChangeProposal proposal = pluginProposal("someone",
                validPluginSource("BadTool", "System.exit(1); return ToolResult.ok(\"never\");"));

        ReviewVerdict verdict = new Reviewer(properties).review(proposal, Reviewer.AGENT_ID);

        assertFalse(verdict.approved());
        assertTrue(verdict.blockers().stream().anyMatch(b -> b.contains("禁止模式")), verdict.blockers().toString());
    }

    @Test
    void 依赖外部包即否决() {
        String source = """
                package com.hivemind.plugins.generated;

                import com.hivemind.agent.RiskLevel;
                import com.hivemind.agent.Tool;
                import com.hivemind.agent.ToolContext;
                import com.hivemind.agent.ToolResult;
                import java.io.File;
                import java.util.Map;

                public class FileTool implements Tool {
                    @Override public String name() { return "file_tool"; }
                    @Override public String description() { return "x"; }
                    @Override public RiskLevel risk() { return RiskLevel.LOW; }
                    @Override public Map<String, String> parameterSchema() { return Map.of("text", "x"); }
                    @Override public ToolResult invoke(ToolContext context, Map<String, Object> args) {
                        return ToolResult.ok(new File("/tmp").getName());
                    }
                }
                """;

        ReviewVerdict verdict = new Reviewer(properties).review(pluginProposal("someone", source), Reviewer.AGENT_ID);

        assertFalse(verdict.approved());
        assertTrue(verdict.blockers().stream().anyMatch(b -> b.contains("禁止依赖外部包")), verdict.blockers().toString());
    }

    @Test
    void 超长源码与非法包名都被拦截() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            body.append("    // 填充行 ").append(i).append('\n');
        }
        ChangeProposal proposal = pluginProposal("someone",
                validPluginSource("LongTool", "return ToolResult.ok(\"ok\");").replace("public class LongTool",
                        body + "    public class LongTool"));

        ReviewVerdict verdict = new Reviewer(properties).review(proposal, Reviewer.AGENT_ID);

        assertFalse(verdict.approved(), "超长源码必须被拦住，避免不可审阅的变更");
    }

    private ChangeProposal pluginProposal(String proposer, String source) {
        String className = classNameOf(source);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("className", className);
        payload.put("packageName", "com.hivemind.plugins.generated");
        payload.put("source", source);
        payload.put("testClassName", className + "Test");
        payload.put("testSource", testSourceFor(className));
        return new ChangeProposal("p-1", ChangeKind.PLUGIN, "新增插件", "补能力", "减少一次模型往返", 20,
                proposer, payload, ProposalStatus.PROPOSED, System.currentTimeMillis());
    }

    /** 测试门禁要求候选自带单元测试，因此合规提案必须包含测试源码。 */
    private String testSourceFor(String className) {
        return """
                package com.hivemind.plugins.generated;

                import com.hivemind.agent.ToolContext;
                import com.hivemind.agent.ToolResult;

                import java.util.Map;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class %sTest {
                    @Test
                    public void 可被调用() {
                        %s tool = new %s();
                        ToolResult result = tool.invoke(new ToolContext("n", "t", "task", true), Map.of("text", "x"));
                        assertTrue(result.success());
                    }
                }
                """.formatted(className, className, className);
    }

    @Test
    void 缺少候选单元测试即被拦下() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("className", "NoTestTool");
        payload.put("packageName", "com.hivemind.plugins.generated");
        payload.put("source", validPluginSource("NoTestTool", "return ToolResult.ok(\"ok\");"));
        ChangeProposal proposal = new ChangeProposal("p-2", ChangeKind.PLUGIN, "无测试插件", "缺测试",
                "提升稳定性", 20, "someone", payload, ProposalStatus.PROPOSED, System.currentTimeMillis());

        ReviewVerdict verdict = new Reviewer(properties).review(proposal, Reviewer.AGENT_ID);

        assertFalse(verdict.approved(), "测试门禁要求候选自带单元测试");
        assertTrue(verdict.blockers().stream().anyMatch(b -> b.contains("测试门禁")), verdict.blockers().toString());
    }

    @Test
    void 测试源码里的禁止模式同样被拦下() {
        String className = "EvilTestTool";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("className", className);
        payload.put("packageName", "com.hivemind.plugins.generated");
        payload.put("source", validPluginSource(className, "return ToolResult.ok(\"ok\");"));
        payload.put("testClassName", className + "Test");
        payload.put("testSource", testSourceFor(className).replace("assertTrue(result.success());", "System.exit(1);"));
        ChangeProposal proposal = new ChangeProposal("p-3", ChangeKind.PLUGIN, "测试里搞破坏", "偷偷退出进程",
                "提升稳定性", 20, "someone", payload, ProposalStatus.PROPOSED, System.currentTimeMillis());

        ReviewVerdict verdict = new Reviewer(properties).review(proposal, Reviewer.AGENT_ID);

        assertFalse(verdict.approved(), "测试代码也要过同一套黑名单，不能成为绕过审查的后门");
        assertTrue(verdict.blockers().stream().anyMatch(b -> b.contains("禁止模式")), verdict.blockers().toString());
    }

    private String classNameOf(String source) {
        int index = source.indexOf("public class ");
        String rest = source.substring(index + "public class ".length()).trim();
        return rest.split("[\\s{]")[0];
    }

    private String validPluginSource(String className, String body) {
        return """
                package com.hivemind.plugins.generated;

                import com.hivemind.agent.RiskLevel;
                import com.hivemind.agent.Tool;
                import com.hivemind.agent.ToolContext;
                import com.hivemind.agent.ToolResult;
                import java.util.LinkedHashMap;
                import java.util.Map;

                public class %s implements Tool {
                    @Override public String name() { return "smoke_tool"; }
                    @Override public String description() { return "演示插件"; }
                    @Override public RiskLevel risk() { return RiskLevel.LOW; }
                    @Override public Map<String, String> parameterSchema() {
                        Map<String, String> schema = new LinkedHashMap<>();
                        schema.put("text", "输入文本");
                        return schema;
                    }
                    @Override public ToolResult invoke(ToolContext context, Map<String, Object> args) {
                        %s
                    }
                }
                """.formatted(className, body);
    }

    @Test
    void 审核结论包含审核者与可读摘要() {
        ReviewVerdict verdict = new Reviewer(properties)
                .review(pluginProposal("someone", validPluginSource("SmokeTool", "return ToolResult.ok(\"ok\");")),
                        Reviewer.AGENT_ID);

        assertTrue(Set.of(Reviewer.AGENT_ID).contains(verdict.reviewer()));
        assertTrue(verdict.summary().contains("风险分"));
    }
}
