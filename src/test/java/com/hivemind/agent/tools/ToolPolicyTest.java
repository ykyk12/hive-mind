package com.hivemind.agent.tools;

import com.hivemind.agent.RiskLevel;
import com.hivemind.agent.ToolContext;
import com.hivemind.agent.ToolResult;
import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 工具的"默认拒绝"策略。
 * 这些断言不联网：验的是护栏本身——未配置、越权主机、越权主题、越权命令都必须被挡住，
 * 而且拒绝理由要能让运维看懂。
 */
class ToolPolicyTest {

    private static final ToolContext CONTEXT = new ToolContext("node-1", "tenant-1", "task-1", true);

    @Test
    void mqtt未配置broker时明确失败() {
        ToolResult result = new MqttPublishTool(new HiveProperties())
                .invoke(CONTEXT, Map.of("topic", "home/light/set", "payload", "on"));

        assertFalse(result.success());
        assertTrue(result.output().contains("未配置 MQTT broker"), result.output());
    }

    @Test
    void mqtt主题不在白名单时拒绝发布() {
        HiveProperties properties = new HiveProperties();
        properties.getAgent().setMqttBrokerUrl("tcp://127.0.0.1:1883");
        properties.getAgent().setMqttTopicAllowPrefixes("home/living_room/");

        ToolResult blocked = new MqttPublishTool(properties)
                .invoke(CONTEXT, Map.of("topic", "home/bedroom/light/set", "payload", "on"));

        assertFalse(blocked.success(), "白名单外的主题必须被拒绝");
        assertTrue(blocked.output().contains("不在白名单内"), blocked.output());
        assertEquals(RiskLevel.MEDIUM, new MqttPublishTool(properties).risk());
    }

    @Test
    void mqtt白名单为空即默认拒绝() {
        HiveProperties properties = new HiveProperties();
        properties.getAgent().setMqttBrokerUrl("tcp://127.0.0.1:1883");

        ToolResult result = new MqttPublishTool(properties)
                .invoke(CONTEXT, Map.of("topic", "home/light/set", "payload", "on"));

        assertFalse(result.success(), "默认必须拒绝，而不是默认放通整个 broker");
        assertTrue(result.output().contains("默认拒绝"), result.output());
    }

    @Test
    void ssh未配置主机时工具不可用() {
        ToolResult result = new SshExecTool(new HiveProperties())
                .invoke(CONTEXT, Map.of("host", "127.0.0.1", "command", "uptime"));

        assertFalse(result.success());
        assertTrue(result.output().contains("未启用"), result.output());
    }

    @Test
    void ssh主机与命令都要过白名单() {
        HiveProperties properties = new HiveProperties();
        properties.getAgent().setSshHosts("ops-node.internal");
        properties.getAgent().setSshUser("ops");
        properties.getAgent().setSshCommandAllowlist(List.of("uptime", "df\\s+-h"));
        SshExecTool tool = new SshExecTool(properties);

        ToolResult wrongHost = tool.invoke(CONTEXT, Map.of("host", "other-node.internal", "command", "uptime"));
        ToolResult wrongCommand = tool.invoke(CONTEXT, Map.of("host", "ops-node.internal", "command", "rm -rf /"));

        assertFalse(wrongHost.success());
        assertTrue(wrongHost.output().contains("主机"), wrongHost.output());
        assertFalse(wrongCommand.success(), "命令白名单外的命令必须拒绝");
        assertTrue(wrongCommand.output().contains("命令不在白名单内"), wrongCommand.output());
        assertEquals(RiskLevel.HIGH, tool.risk());
    }

    @Test
    void ssh命令白名单为空时一律拒绝() {
        HiveProperties properties = new HiveProperties();
        properties.getAgent().setSshHosts("ops-node.internal");
        properties.getAgent().setSshUser("ops");

        ToolResult result = new SshExecTool(properties)
                .invoke(CONTEXT, Map.of("host", "ops-node.internal", "command", "uptime"));

        assertFalse(result.success());
        assertTrue(result.output().contains("命令白名单为空"), result.output());
    }

    @Test
    void 网页读取的主机白名单生效() {
        ToolResult blocked = new WebReaderTool(new HiveProperties())
                .invoke(CONTEXT, Map.of("url", "https://example.com/x"));

        assertFalse(blocked.success());
        assertTrue(blocked.output().contains("不在白名单内"), blocked.output());
        assertEquals(RiskLevel.MEDIUM, new WebReaderTool(new HiveProperties()).risk());
    }

    @Test
    void 网页提交是高风险且同样受白名单约束() {
        HiveProperties properties = new HiveProperties();
        ToolResult blocked = new WebSubmitTool(properties)
                .invoke(CONTEXT, Map.of("url", "https://example.com/submit", "body", "{}"));

        assertFalse(blocked.success());
        assertTrue(blocked.output().contains("不在白名单内"), blocked.output());
        assertEquals(RiskLevel.HIGH, new WebSubmitTool(properties).risk());
    }

    @Test
    void 网页正文与链接提取可用() {
        String html = """
                <html><head><title>示例页面</title>
                <script>var x = 1;</script><style>body{color:red}</style></head>
                <body>
                  <h1>标题一</h1>
                  <p>第一段&nbsp;正文</p>
                  <a href="/a">链接A</a>
                  <a href="#anchor">锚点</a>
                  <a href="javascript:void(0)">脚本链接</a>
                </body></html>
                """;

        WebReaderTool.Extracted extracted = WebReaderTool.extract(html, 500);

        assertEquals("示例页面", extracted.title());
        assertTrue(extracted.text().contains("第一段 正文"), extracted.text());
        assertFalse(extracted.text().contains("var x = 1"), "script 内容不应进入正文：" + extracted.text());
        assertFalse(extracted.text().contains("color:red"), "style 内容不应进入正文：" + extracted.text());
        assertTrue(extracted.links().stream().anyMatch(link -> link.contains("链接A")), extracted.links().toString());
        assertTrue(extracted.links().stream().noneMatch(link -> link.contains("javascript:")),
                "脚本链接必须被过滤：" + extracted.links());
        assertTrue(extracted.links().stream().noneMatch(link -> link.contains("#anchor")),
                "页内锚点不是有效目标：" + extracted.links());
    }

    @Test
    void 网页正文超长时截断() {
        String html = "<p>" + "很长的正文".repeat(200) + "</p>";

        WebReaderTool.Extracted extracted = WebReaderTool.extract(html, 100);

        assertTrue(extracted.text().length() <= 120, "必须截断，避免把上下文挤爆：" + extracted.text().length());
        assertTrue(extracted.text().endsWith("...(已截断)"));
    }
}
