package com.hivemind.agent.tools;

import com.hivemind.agent.RiskLevel;
import com.hivemind.agent.Tool;
import com.hivemind.agent.ToolContext;
import com.hivemind.agent.ToolResult;
import com.hivemind.common.Ids;
import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT 直连发布（智能家居/物联网设备的通用入口，不再依赖 HTTP 桥接）。
 *
 * 安全设计（默认拒绝）：
 * - 未配置 broker → 直接失败，不静默成功；
 * - 主题必须命中前缀白名单，**白名单默认是空的**：宁可用不了，也不默认放通整个 broker；
 * - MEDIUM 风险：需要审批令牌才会真正发布；
 * - 每次调用用完即断开（cleanSession），不长期持有连接，避免"自改插件顺手劫持连接"。
 */
@Slf4j
public final class MqttPublishTool implements Tool {

    private final HiveProperties properties;

    public MqttPublishTool(HiveProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "mqtt_publish";
    }

    @Override
    public String description() {
        return "向 MQTT 主题发布消息（智能家居控制）。参数：topic 主题，payload 消息内容，retained 是否保留";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public Map<String, String> parameterSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("topic", "MQTT 主题，例如 home/living_room/light/set");
        schema.put("payload", "消息内容（字符串）");
        return schema;
    }

    @Override
    public ToolResult invoke(ToolContext context, Map<String, Object> args) {
        HiveProperties.Agent config = properties.getAgent();
        if (config.getMqttBrokerUrl() == null || config.getMqttBrokerUrl().isBlank()) {
            return ToolResult.fail("未配置 MQTT broker（hive.agent.mqtt-broker-url 或环境变量 HIVE_MQTT_BROKER_URL）");
        }
        String topic = String.valueOf(args.getOrDefault("topic", "")).trim();
        if (topic.isBlank()) {
            return ToolResult.fail("topic 必填");
        }
        List<String> allowedPrefixes = HiveProperties.splitCsv(config.getMqttTopicAllowPrefixes());
        boolean topicAllowed = allowedPrefixes.stream().anyMatch(topic::startsWith);
        if (!topicAllowed) {
            return ToolResult.fail("主题 " + topic + " 不在白名单内（允许的前缀=" + allowedPrefixes + "）；"
                    + "白名单为空表示默认拒绝，请在 hive.agent.mqtt-topic-allow-prefixes 中显式放宽");
        }
        String payload = String.valueOf(args.getOrDefault("payload", ""));

        String clientId = config.getMqttClientId() + "-" + Ids.shortId().substring(0, 6);
        MqttClient client = null;
        try {
            client = new MqttClient(config.getMqttBrokerUrl(), clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout((int) Math.max(1, config.getMqttTimeoutMillis() / 1000));
            if (config.getMqttUsername() != null && !config.getMqttUsername().isBlank()) {
                options.setUserName(config.getMqttUsername());
                options.setPassword(config.getMqttPassword() == null ? new char[0] : config.getMqttPassword().toCharArray());
            }
            client.connect(options);
            MqttMessage message = new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            message.setQos(Math.max(0, Math.min(config.getMqttQos(), 2)));
            message.setRetained(false);
            client.publish(topic, message);
            log.info("MQTT 发布成功 topic={} qos={} bytes={}（任务 {}）",
                    topic, message.getQos(), payload.length(), context.taskId());
            return ToolResult.ok("已发布到 " + topic + "（qos=" + message.getQos() + "，"
                    + payload.length() + " 字节）");
        } catch (Exception e) {
            return ToolResult.fail("MQTT 发布失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (client != null) {
                try {
                    if (client.isConnected()) {
                        client.disconnect();
                    }
                    client.close();
                } catch (Exception e) {
                    log.debug("关闭 MQTT 连接失败：{}", e.getMessage());
                }
            }
        }
    }
}
