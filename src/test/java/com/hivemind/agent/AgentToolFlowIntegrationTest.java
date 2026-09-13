package com.hivemind.agent;

import com.hivemind.model.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agent 循环 + 风险门的端到端行为（用本地确定性模型，不联网）。 */
@SpringBootTest(properties = {
        "hive.node.peers=",
        "hive.node.heartbeat-millis=500",
        "hive.node.lease-millis=1500",
        "hive.evolve.root=target/hive-agent-test"
})
class AgentToolFlowIntegrationTest {

    @Autowired
    private AgentLoop agentLoop;

    @Test
    void 调用低风险工具并基于工具结果作答() {
        AgentResult result = agentLoop.run(AgentTask.of("task-echo", "tenant-1",
                "CALL_TOOL:echo {\"text\":\"hi\"}", TaskType.REASONING, false, 4));

        assertTrue(result.success(), "错误信息=" + result.error());
        assertTrue(result.steps().size() >= 2, "至少要有一次工具调用 + 一次作答");
        AgentStep toolStep = result.steps().get(0);
        assertEquals("echo", toolStep.toolName());
        assertTrue(toolStep.toolSuccess());
        assertTrue(result.answer().contains("hi"), "答案应基于工具返回值：" + result.answer());
        assertEquals("task-echo", result.taskId());
    }

    @Test
    void 中风险工具无审批令牌时被风险门拦下() {
        AgentResult result = agentLoop.run(AgentTask.of("task-smart-home", "tenant-1",
                "CALL_TOOL:smart_home {\"device\":\"living_room_light\",\"action\":\"on\"}",
                TaskType.REASONING, false, 2));

        AgentStep denied = result.steps().get(0);
        assertEquals("smart_home", denied.toolName());
        assertFalse(denied.toolSuccess(), "MEDIUM 风险工具不带审批令牌不能被真正执行");
        assertTrue(denied.observation().contains("审批令牌"), "拒绝原因要能看懂：" + denied.observation());
    }

    @Test
    void 带了审批令牌才允许执行中风险工具() {
        AgentResult result = agentLoop.run(AgentTask.of("task-approved", "tenant-1",
                "CALL_TOOL:smart_home {\"device\":\"living_room_light\",\"action\":\"on\"}",
                TaskType.REASONING, true, 2));

        AgentStep step = result.steps().get(0);
        assertEquals("smart_home", step.toolName());
        // 已配置桥接地址时才可能成功；未配置时必须是"工具执行失败"，而不是被门禁拦下
        assertTrue(step.observation().contains("桥接地址") || step.toolSuccess(),
                "审批放行后应真正执行工具：" + step.observation());
    }
}
