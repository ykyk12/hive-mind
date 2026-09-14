package com.hivemind.model;

import com.hivemind.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 模型编排的观测入口：能看到"现在会选谁、为什么、各家健康度"。 */
@Tag(name = "模型路由", description = "模型提供方列表、健康度、并行 fanout 对照")
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelRouter router;

    @Operation(summary = "列出模型提供方与健康度")
    @GetMapping
    public ApiResponse<List<ModelView>> list() {
        return ApiResponse.ok(router.views());
    }

    /** 同一问题并行问多家，用于人工对照（不做自动合并，合并策略见 docs/ARCHITECTURE.md）。 */
    @Operation(summary = "并行 fanout 多家模型", description = "同一问题并行问多家，用于人工对照，不做自动合并。")
    @PostMapping("/fanout")
    public ApiResponse<List<CompletionResponse>> fanout(@RequestBody FanoutRequest request,
                                                       @RequestParam(defaultValue = "3") int maxProviders) {
        CompletionRequest completion = new CompletionRequest(
                request.taskType() == null ? TaskType.CHAT : request.taskType(),
                request.systemPrompt(),
                List.of(ChatMessage.user(request.input())),
                512,
                0.2,
                false);
        return ApiResponse.ok(router.fanout(completion, maxProviders));
    }

    public record FanoutRequest(String input, TaskType taskType, String systemPrompt) {
    }
}
