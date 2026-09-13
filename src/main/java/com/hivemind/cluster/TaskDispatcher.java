package com.hivemind.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.agent.AgentLoop;
import com.hivemind.agent.AgentResult;
import com.hivemind.agent.AgentTask;
import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务下发：大脑按能力把任务派给合适的神经元；没有合适节点（或远端失败）时在本地执行。
 *
 * "大脑"并不是特权角色：它同样能执行任务，只是多了一份调度职责——
 * 这正是同构节点设计的好处：任何节点都具备成为大脑的完整能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDispatcher {

    private final HiveProperties properties;
    private final NodeRegistry registry;
    private final AgentLoop agentLoop;
    private final ObjectMapper objectMapper;

    private final AtomicInteger roundRobin = new AtomicInteger();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public AgentResult dispatch(AgentTask task, String capability) {
        Optional<NodeDescriptor> target = pickNeuron(capability);
        if (target.isPresent() && !target.get().endpoint().equals(registry.self().endpoint())) {
            try {
                AgentResult remote = dispatchRemote(target.get(), task);
                if (remote != null) {
                    return remote;
                }
            } catch (RuntimeException e) {
                log.warn("远端节点 {} 执行失败，回退本地执行：{}", target.get().nodeId(), e.getMessage());
            }
        }
        return agentLoop.run(task);
    }

    /** 选一个存活、非大脑、且具备所需能力的神经元（轮询，避免总打同一个）。 */
    Optional<NodeDescriptor> pickNeuron(String capability) {
        List<NodeDescriptor> candidates = registry.alivePeers().stream()
                .filter(peer -> !peer.isBrain())
                .filter(peer -> capability == null || capability.isBlank() || peer.capabilities().contains(capability))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(Math.floorMod(roundRobin.getAndIncrement(), candidates.size())));
    }

    private AgentResult dispatchRemote(NodeDescriptor target, AgentTask task) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", task.taskId());
        body.put("tenantId", task.tenantId());
        body.put("input", task.input());
        body.put("taskType", task.taskType());
        body.put("approve", task.approved());
        body.put("maxSteps", task.maxSteps());
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(target.endpoint() + "/api/v1/tasks"))
                    .timeout(Duration.ofSeconds(60))
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
                throw new IllegalStateException("远端 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new IllegalStateException("远端响应缺少 data");
            }
            log.info("任务 {} 已由神经元 {} 执行（nodeId={}）", task.taskId(), target.nodeId(),
                    data.path("nodeId").asText("?"));
            return objectMapper.treeToValue(data, AgentResult.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务下发被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }

    public boolean isBrain() {
        return registry.self().isBrain();
    }
}
