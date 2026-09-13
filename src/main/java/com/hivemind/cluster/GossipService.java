package com.hivemind.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.config.HiveProperties;
import com.hivemind.skill.SkillArtifact;
import com.hivemind.skill.SkillStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经验反熵（gossip）：大脑把"已通过跨节点验证"的技能推给神经元；神经元也可以主动拉。
 *
 * 只传播 promotable 的技能，是防崩溃传染的核心设计：
 * 某个节点自己试出来、还没被别的节点验证过的"经验"，一律留在本机，不许扩散。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GossipService {

    private final HiveProperties properties;
    private final SkillStore skillStore;
    private final NodeRegistry registry;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** 大脑侧：把已推广技能推给所有同伴。 */
    public void pushToPeers() {
        List<SkillArtifact> catalog = catalog();
        if (catalog.isEmpty()) {
            return;
        }
        for (NodeDescriptor peer : registry.peers()) {
            pushTo(peer.endpoint(), catalog);
        }
    }

    public int pushTo(String endpoint, List<SkillArtifact> artifacts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeId", registry.self().nodeId());
        body.put("artifacts", artifacts);
        try {
            JsonNode root = post(endpoint + "/api/v1/cluster/skills/accept", body);
            return root.path("data").path("accepted").asInt(0);
        } catch (RuntimeException e) {
            log.debug("向 {} 推送技能失败：{}", endpoint, e.getMessage());
            return 0;
        }
    }

    /** 神经元侧：从大脑拉取已推广技能。 */
    public int pullFrom(String brainEndpoint) {
        try {
            JsonNode root = get(brainEndpoint + "/api/v1/cluster/skills/catalog");
            JsonNode data = root.path("data");
            int count = 0;
            List<SkillArtifact> accepted = new ArrayList<>();
            for (JsonNode node : data) {
                SkillArtifact artifact = objectMapper.treeToValue(node, SkillArtifact.class);
                accepted.add(artifact);
                skillStore.upsertFromPeer(artifact, node.path("originNodeId").asText("remote"));
                count++;
            }
            if (count > 0) {
                log.info("从 {} 拉取并接收 {} 条技能", brainEndpoint, count);
            }
            return count;
        } catch (RuntimeException e) {
            log.debug("从 {} 拉取技能失败：{}", brainEndpoint, e.getMessage());
            return 0;
        } catch (Exception e) {
            log.debug("技能反序列化失败：{}", e.getMessage());
            return 0;
        }
    }

    public int accept(List<SkillArtifact> artifacts, String peerNodeId) {
        int count = 0;
        for (SkillArtifact artifact : artifacts) {
            skillStore.upsertFromPeer(artifact, peerNodeId == null ? "remote" : peerNodeId);
            count++;
        }
        return count;
    }

    /** 只广播够格的技能（跨节点验证 + 适应度 + 使用次数三重门槛）。 */
    public List<SkillArtifact> catalog() {
        return skillStore.promotableArtifacts();
    }

    private JsonNode get(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET();
            applyToken(builder);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gossip 拉取被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }

    private JsonNode post(String url, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json");
            applyToken(builder);
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
            throw new IllegalStateException("gossip 推送被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }

    private void applyToken(HttpRequest.Builder builder) {
        String token = properties.getNode().getClusterToken();
        if (token != null && !token.isBlank()) {
            builder.header("X-Hive-Cluster-Token", token);
        }
    }
}
