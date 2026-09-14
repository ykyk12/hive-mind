package com.hivemind.agent;

import com.hivemind.common.ApiResponse;
import com.hivemind.common.Ids;
import com.hivemind.model.TaskType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务入口：跑一次 Agent 循环，并暴露工具目录与待确认的高危调用。 */
@Tag(name = "Agent 任务", description = "任务执行、工具目录、待人工确认的高危调用")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {

    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final ConfirmationGate confirmationGate;

    @Operation(summary = "运行一次 Agent 循环", description = "给定输入跑一轮工具调用循环；高危调用会被确认门拦截，带 approve=true 重跑放行。")
    @PostMapping("/tasks")
    public ApiResponse<AgentResult> run(@RequestBody AgentRequest request) {
        if (request.input() == null || request.input().isBlank()) {
            throw new IllegalArgumentException("input 不能为空");
        }
        String taskId = request.taskId() == null || request.taskId().isBlank() ? Ids.shortId() : request.taskId();
        AgentTask task = AgentTask.of(taskId, request.tenantId(), request.input(),
                request.taskType(), Boolean.TRUE.equals(request.approve()),
                request.maxSteps() == null ? 0 : request.maxSteps());
        return ApiResponse.ok(agentLoop.run(task));
    }

    /** 工具目录 + 版本历史：能看出哪些工具是内置的、哪些是进化产物。 */
    @Operation(summary = "工具目录与版本历史")
    @GetMapping("/tools")
    public ApiResponse<Map<String, Object>> tools() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", toolRegistry.size());
        body.put("catalog", toolRegistry.catalog());
        body.put("versions", toolRegistry.all().stream()
                .map(tool -> Map.of("name", tool.name(), "source", tool.source(), "risk", tool.risk(),
                        "history", toolRegistry.versions(tool.name()).size()))
                .toList());
        return ApiResponse.ok(body);
    }

    /** 被风险门拦下的调用：人工确认后带 approve=true 重跑即可放行。 */
    @Operation(summary = "待人工确认的高危调用")
    @GetMapping("/pending-confirmations")
    public ApiResponse<List<ConfirmationGate.PendingConfirmation>> pending() {
        return ApiResponse.ok(List.copyOf(confirmationGate.pending().values()));
    }

    public record AgentRequest(String taskId,
                               String tenantId,
                               String input,
                               TaskType taskType,
                               Boolean approve,
                               Integer maxSteps) {
    }
}
