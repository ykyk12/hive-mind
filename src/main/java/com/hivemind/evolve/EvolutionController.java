package com.hivemind.evolve;

import com.hivemind.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 制品与回滚接口（受 X-Hive-Admin-Key 保护）：证明"改坏了能退回来"。 */
@RestController
@RequestMapping("/api/v1/evolution")
@RequiredArgsConstructor
public class EvolutionController {

    private final EvolutionPipeline pipeline;

    @GetMapping("/artifacts")
    public ApiResponse<List<ArtifactStore.ArtifactRecord>> artifacts() {
        return ApiResponse.ok(pipeline.history());
    }

    @GetMapping("/active")
    public ApiResponse<Map<String, Object>> active() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", pipeline.activeArtifacts());
        body.put("hotConfig", pipeline.hotConfigValues());
        body.put("workspaceRoot", pipeline.workspaceRoot());
        return ApiResponse.ok(body);
    }

    @PostMapping("/rollback")
    public ApiResponse<ApplyResult> rollback(@RequestParam String name) {
        return ApiResponse.ok(pipeline.rollback(name));
    }
}
