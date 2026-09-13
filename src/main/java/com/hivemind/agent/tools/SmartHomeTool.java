package com.hivemind.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.agent.RiskLevel;
import com.hivemind.agent.Tool;
import com.hivemind.agent.ToolContext;
import com.hivemind.agent.ToolResult;
import com.hivemind.config.HiveProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能家居控制（通过 HTTP 桥接网关，例如 Home Assistant REST API 或自建 MQTT 网关）。
 *
 * 为什么是 MEDIUM：开关灯、开空调这类操作有真实世界副作用，且用户不在现场时无法纠错，
 * 所以必须带审批令牌执行；同时把"未配置桥接地址"当成失败而不是静默成功。
 */
public final class SmartHomeTool implements Tool {

    private final HiveProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public SmartHomeTool(HiveProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getAgent().getHttpTimeoutMillis()))
                .build();
    }

    @Override
    public String name() {
        return "smart_home";
    }

    @Override
    public String description() {
        return "控制智能家居设备（灯/空调/插座等），参数：device 设备标识，action 动作(on/off/set)，value 可选数值";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public Map<String, String> parameterSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("device", "设备标识，例如 living_room_light");
        schema.put("action", "动作：on / off / set");
        schema.put("value", "可选数值（action=set 时使用）");
        return schema;
    }

    @Override
    public ToolResult invoke(ToolContext context, Map<String, Object> args) {
        String url = properties.getAgent().getSmartHomeUrl();
        if (url == null || url.isBlank()) {
            return ToolResult.fail("未配置智能家居桥接地址（hive.agent.smart-home-url 或环境变量 HIVE_SMART_HOME_URL）");
        }
        String device = String.valueOf(args.getOrDefault("device", ""));
        String action = String.valueOf(args.getOrDefault("action", ""));
        if (device.isBlank() || action.isBlank()) {
            return ToolResult.fail("device 与 action 均为必填");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("device", device);
            payload.put("action", action);
            payload.put("value", args.get("value"));
            payload.put("taskId", context.taskId());
            payload.put("nodeId", context.nodeId());

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getAgent().getHttpTimeoutMillis()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            String token = properties.getAgent().getSmartHomeToken();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return ToolResult.fail("桥接网关返回 " + response.statusCode() + "：" + response.body());
            }
            return ToolResult.ok("已下发 " + device + " " + action + "，网关返回：" + response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("调用被中断");
        } catch (Exception e) {
            return ToolResult.fail("调用桥接网关失败：" + e.getMessage());
        }
    }
}
