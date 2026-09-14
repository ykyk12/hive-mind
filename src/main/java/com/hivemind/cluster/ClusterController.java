package com.hivemind.cluster;

import com.hivemind.agent.AgentController;
import com.hivemind.agent.AgentResult;
import com.hivemind.agent.AgentTask;
import com.hivemind.common.ApiResponse;
import com.hivemind.common.Ids;
import com.hivemind.model.TaskType;
import com.hivemind.skill.SkillArtifact;
import com.hivemind.skill.SkillStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集群接口（节点间调用，可选密钥保护）。
 *
 * 演示三节点的最小命令序列：启动三个进程 → GET /state 看角色 → 杀掉大脑 → 再 GET /state 看新大脑产生。
 */
@Tag(name = "集群", description = "Raft 选主/心跳/技能广播/任务派发（节点间调用）")
@RestController
@RequestMapping("/api/v1/cluster")
@RequiredArgsConstructor
public class ClusterController {

    private final NodeRegistry registry;
    private final ElectionService election;
    private final GossipService gossip;
    private final TaskDispatcher dispatcher;
    private final SkillStore skillStore;
    private final HeartbeatService heartbeatService;

    @Operation(summary = "发起投票")
    @PostMapping("/vote")
    public ApiResponse<PeerTransport.VoteResponse> vote(@RequestBody VoteRequest request) {
        return ApiResponse.ok(election.handleVote(request.candidateId(), request.term(), request.weight(),
                request.endpoint(), toSet(request.capabilities())));
    }

    @Operation(summary = "大脑心跳")
    @PostMapping("/heartbeat")
    public ApiResponse<PeerTransport.HeartbeatAck> heartbeat(@RequestBody BrainHeartbeatRequest request) {
        return ApiResponse.ok(election.handleBrainHeartbeat(request.brainId(), request.term(),
                request.endpoint(), toSet(request.capabilities()), request.weight()));
    }

    @Operation(summary = "神经元心跳")
    @PostMapping("/beat")
    public ApiResponse<PeerTransport.HeartbeatAck> beat(@RequestBody NeuronBeatRequest request) {
        return ApiResponse.ok(election.handleNeuronBeat(request.nodeId(), request.term(), request.endpoint(),
                toSet(request.capabilities()), request.weight()));
    }

    @Operation(summary = "节点加入集群")
    @PostMapping("/join")
    public ApiResponse<Map<String, Object>> join(@RequestBody JoinRequest request) {
        registry.upsertPeer(new NodeDescriptor(request.nodeId(), request.endpoint(),
                toSet(request.capabilities()), request.weight(), 0L, NodeRole.NEURON,
                System.currentTimeMillis()));
        return ApiResponse.ok(state());
    }

    @Operation(summary = "集群状态视图")
    @GetMapping("/state")
    public ApiResponse<Map<String, Object>> stateView() {
        return ApiResponse.ok(state());
    }

    /** 大脑侧：已通过跨节点验证、可以广播的技能目录。 */
    @Operation(summary = "可广播技能目录（大脑侧）")
    @GetMapping("/skills/catalog")
    public ApiResponse<List<SkillArtifact>> catalog() {
        return ApiResponse.ok(gossip.catalog());
    }

    /** 神经元侧：接收大脑推来的技能。 */
    @Operation(summary = "接收下发技能（神经元侧）")
    @PostMapping("/skills/accept")
    public ApiResponse<Map<String, Object>> accept(@RequestBody AcceptSkillsRequest request) {
        int accepted = gossip.accept(request.artifacts() == null ? List.of() : request.artifacts(),
                request.nodeId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", accepted);
        body.put("localSkills", skillStore.size());
        return ApiResponse.ok(body);
    }

    /** 手动触发一次下发（演示"大脑把活派给神经元"）。 */
    @Operation(summary = "派发任务到神经元")
    @PostMapping("/tasks")
    public ApiResponse<AgentResult> dispatch(@RequestBody AgentController.AgentRequest request,
                                             @RequestParam(required = false) String capability) {
        String taskId = request.taskId() == null || request.taskId().isBlank() ? Ids.shortId() : request.taskId();
        AgentTask task = AgentTask.of(taskId, request.tenantId(), request.input(),
                request.taskType() == null ? TaskType.REASONING : request.taskType(),
                Boolean.TRUE.equals(request.approve()), request.maxSteps() == null ? 0 : request.maxSteps());
        return ApiResponse.ok(dispatcher.dispatch(task, capability));
    }

    private Map<String, Object> state() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("self", registry.self());
        body.put("role", registry.self().role().name());
        body.put("term", election.term());
        body.put("brainId", election.brainId() == null ? null : election.brainId());
        body.put("leaseUntilMillis", election.leaseUntilMillis());
        body.put("clusterSize", registry.clusterSize());
        body.put("quorum", registry.quorum());
        body.put("heartbeatMillis", heartbeatService.heartbeatMillis());
        body.put("peers", registry.peers());
        body.put("alivePeers", registry.alivePeers());
        body.put("skillCount", skillStore.size());
        body.put("promotableSkills", skillStore.promotableArtifacts().size());
        return body;
    }

    private Set<String> toSet(List<String> raw) {
        return raw == null ? Set.of() : Set.copyOf(raw);
    }

    public record VoteRequest(String candidateId, long term, int weight, String endpoint,
                              List<String> capabilities) {
    }

    public record BrainHeartbeatRequest(String brainId, long term, int weight, String endpoint,
                                        List<String> capabilities) {
    }

    public record NeuronBeatRequest(String nodeId, long term, int weight, String endpoint,
                                    List<String> capabilities) {
    }

    public record JoinRequest(String nodeId, String endpoint, List<String> capabilities, int weight) {
    }

    public record AcceptSkillsRequest(String nodeId, List<SkillArtifact> artifacts) {
    }
}
