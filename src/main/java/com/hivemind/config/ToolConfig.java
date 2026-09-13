package com.hivemind.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.agent.ToolRegistry;
import com.hivemind.agent.tools.EchoTool;
import com.hivemind.agent.tools.HttpJsonTool;
import com.hivemind.agent.tools.MqttPublishTool;
import com.hivemind.agent.tools.SmartHomeTool;
import com.hivemind.agent.tools.SshExecTool;
import com.hivemind.agent.tools.TimeTool;
import com.hivemind.agent.tools.WebReaderTool;
import com.hivemind.agent.tools.WebSubmitTool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** 内置工具装配。进化产生的插件走 ToolRegistry.register(tool, "plugin@版本") 动态注册。 */
@Configuration
@RequiredArgsConstructor
public class ToolConfig {

    private final ToolRegistry toolRegistry;
    private final HiveProperties properties;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void registerBuiltinTools() {
        toolRegistry.register(new EchoTool(), "builtin");
        toolRegistry.register(new TimeTool(), "builtin");
        toolRegistry.register(new HttpJsonTool(properties), "builtin");
        toolRegistry.register(new SmartHomeTool(properties, objectMapper), "builtin");
        // M4 工具面：默认关闭（白名单为空即不可用），配置后自动生效
        toolRegistry.register(new MqttPublishTool(properties), "builtin");
        toolRegistry.register(new SshExecTool(properties), "builtin");
        toolRegistry.register(new WebReaderTool(properties), "builtin");
        toolRegistry.register(new WebSubmitTool(properties), "builtin");
    }
}
