package com.hivemind.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 基于 JDK HttpClient 的节点传输实现（不引 SDK，减少依赖面）。 */
@Slf4j
@Component
public class HttpPeerTransport implements PeerTransport {

    private final HiveProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public HttpPeerTransport(HiveProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(300, properties.getNode().getHeartbeatMillis())))
                .build();
    }

    @Override
    public VoteResponse requestVote(String endpoint, String candidateId, long term, int weight) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidateId", candidateId);
        body.put("term", term);
        body.put("weight", weight);
        body.put("endpoint", properties.getNode().getEndpoint());
        body.put("capabilities", properties.getNode().capabilitySet());
        try {
            JsonNode root = post(endpoint + "/api/v1/cluster/vote", body);
            JsonNode data = root.path("data");
            return new VoteResponse(data.path("granted").asBoolean(false), data.path("term").asLong(term));
        } catch (RuntimeException e) {
            log.debug("向 {} 拉票失败：{}", endpoint, e.getMessage());
            return new VoteResponse(false, term);
        }
    }

    @Override
    public HeartbeatAck heartbeat(String endpoint, String brainId, long term, Set<String> capabilities,
                                  int weight, String selfEndpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brainId", brainId);
        body.put("term", term);
        body.put("weight", weight);
        body.put("endpoint", selfEndpoint);
        body.put("capabilities", capabilities);
        try {
            JsonNode root = post(endpoint + "/api/v1/cluster/heartbeat", body);
            JsonNode data = root.path("data");
            return new HeartbeatAck(data.path("ok").asBoolean(false), data.path("term").asLong(term),
                    data.path("role").asText("NEURON"));
        } catch (RuntimeException e) {
            log.debug("向 {} 发送心跳失败：{}", endpoint, e.getMessage());
            return new HeartbeatAck(false, term, "UNKNOWN");
        }
    }

    JsonNode post(String url, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json");
            String token = properties.getNode().getClusterToken();
            if (token != null && !token.isBlank()) {
                builder.header("X-Hive-Cluster-Token", token);
            }
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("节点调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }
}
