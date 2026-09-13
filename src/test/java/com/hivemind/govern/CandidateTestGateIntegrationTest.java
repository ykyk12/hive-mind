package com.hivemind.govern;

import com.hivemind.agent.ToolRegistry;
import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.change.GateDecision;
import com.hivemind.change.ReviewVerdict;
import com.hivemind.common.Ids;
import com.hivemind.config.HiveProperties;
import com.hivemind.evolve.ApplyResult;
import com.hivemind.evolve.EvolutionPipeline;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 真实测试门禁：候选插件必须自带单元测试，且测试要在隔离类加载器里真实跑通才允许生效。
 *
 * 这里验的是"门禁真的会拦人"：测试失败的候选必须被拒绝，且拒绝理由可读。
 * 只验证"能通过"是不够的——不会拦人的门禁等于没有门禁。
 */
@SpringBootTest(properties = {
        "hive.node.peers=",
        "hive.node.heartbeat-millis=1000",
        "hive.node.lease-millis=3000",
        "hive.evolve.root=target/hive-testgate",
        "hive.evolve.require-tests=true"
})
class CandidateTestGateIntegrationTest {

    private static final String PACKAGE = "com.hivemind.plugins.generated";

    @Autowired
    private GovernanceService governance;
    @Autowired
    private EvolutionPipeline pipeline;
    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private HiveProperties properties;

    @Test
    void 测试全部通过的候选才允许生效() {
        ApplyResult result = apply(pluginProposal("GateProbeTool", passingTest()));

        assertTrue(result.applied(), "应用失败原因=" + result.message() + " 步骤=" + result.steps());
        assertTrue(result.steps().stream().anyMatch(step -> step.contains("候选单元测试全部通过")),
                "步骤里必须能看到测试结果：" + result.steps());
        assertTrue(toolRegistry.find("gate_probe").isPresent());
    }

    @Test
    void 测试失败的候选被拒绝且理由可读() {
        ApplyResult result = apply(pluginProposal("GateProbeTool", failingTest()));

        assertFalse(result.applied(), "测试没过就不许生效");
        assertTrue(result.message().contains("测试门禁未通过"), result.message());
        assertTrue(result.steps().stream().anyMatch(step -> step.contains("候选单元测试")),
                "要能看到测试步骤：" + result.steps());
    }

    @Test
    void 没有自带测试的候选在流水线层被拒绝() {
        Map<String, Object> payload = pluginPayload("GateProbeTool", pluginSource());
        ChangeProposal proposal = new ChangeProposal("test-gate-no-tests", ChangeKind.PLUGIN,
                "无测试的插件", "想跳过测试", "提升稳定性", 20, "someone", payload,
                com.hivemind.change.ProposalStatus.PROPOSED, System.currentTimeMillis());

        // 直接打到流水线：即便审核环节被绕过，流水线自己也必须拦住
        ApplyResult result = pipeline.apply(proposal);

        assertFalse(result.applied());
        assertTrue(result.message().contains("测试门禁未满足"), result.message());
    }

    @Test
    void 生效的插件由独立类加载器加载() {
        ApplyResult result = apply(pluginProposal("GateProbeTool", passingTest()));

        assertTrue(result.applied(), result.message());
        ClassLoader pluginClassLoader = toolRegistry.find("gate_probe").orElseThrow().getClass().getClassLoader();
        assertNotEquals(CandidateTestGateIntegrationTest.class.getClassLoader(), pluginClassLoader,
                "插件必须由隔离类加载器加载，而不是与应用同一个加载器（否则就没有隔离可言）");
    }

    private ApplyResult apply(ChangeProposal proposal) {
        ChangeProposal submitted = governance.submit(proposal.kind(), proposal.title(), proposal.rationale(),
                proposal.expectedBenefit(), proposal.declaredRisk(), proposal.proposer(), proposal.payload());
        ReviewVerdict verdict = governance.review(submitted.proposalId(), Reviewer.AGENT_ID);
        assertTrue(verdict.approved(), "审核阻断项=" + verdict.blockers());
        GateDecision decision = governance.gate(submitted.proposalId());
        assertTrue(decision.allowed(), "门锁原因=" + decision.reasons());
        return governance.apply(submitted.proposalId());
    }

    private ChangeProposal pluginProposal(String className, String testSource) {
        Map<String, Object> payload = pluginPayload(className, pluginSource());
        payload.put("testClassName", className + "Test");
        payload.put("testSource", testSource);
        return new ChangeProposal("test-gate-" + Ids.shortId(), ChangeKind.PLUGIN,
                "测试门禁探针插件", "验证测试门禁行为", "证明门禁会拦下不合格变更", 20,
                "proposer-agent", payload, com.hivemind.change.ProposalStatus.PROPOSED, System.currentTimeMillis());
    }

    private Map<String, Object> pluginPayload(String className, String source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("className", className);
        payload.put("packageName", PACKAGE);
        payload.put("source", source);
        return payload;
    }

    private String pluginSource() {
        return """
                package com.hivemind.plugins.generated;

                import com.hivemind.agent.RiskLevel;
                import com.hivemind.agent.Tool;
                import com.hivemind.agent.ToolContext;
                import com.hivemind.agent.ToolResult;

                import java.util.Map;

                /** 测试门禁探针：只依赖 agent 接口，便于隔离编译。 */
                public class GateProbeTool implements Tool {
                    @Override public String name() { return "gate_probe"; }
                    @Override public String description() { return "测试门禁探针"; }
                    @Override public RiskLevel risk() { return RiskLevel.LOW; }
                    @Override public Map<String, String> parameterSchema() { return Map.of("text", "输入文本"); }
                    @Override public ToolResult invoke(ToolContext context, Map<String, Object> args) {
                        return ToolResult.ok("probe:" + args.getOrDefault("text", ""));
                    }
                }
                """;
    }

    private String passingTest() {
        return """
                package com.hivemind.plugins.generated;

                import com.hivemind.agent.ToolContext;
                import com.hivemind.agent.ToolResult;

                import java.util.Map;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class GateProbeToolTest {
                    @Test
                    public void 返回带前缀的结果() {
                        GateProbeTool tool = new GateProbeTool();
                        ToolResult result = tool.invoke(new ToolContext("n", "t", "task", true), Map.of("text", "abc"));
                        assertTrue(result.success());
                        assertEquals("probe:abc", result.output());
                    }
                }
                """;
    }

    private String failingTest() {
        return passingTest().replace("assertEquals(\"probe:abc\", result.output());",
                "assertEquals(\"probe:这个断言是错的\", result.output());");
    }

    @Test
    void 测试门禁默认开启() {
        assertTrue(properties.getEvolve().isRequireTests(), "默认必须要求候选自带测试");
        assertTrue(properties.getEvolve().getTestTimeoutSeconds() > 0);
    }
}
