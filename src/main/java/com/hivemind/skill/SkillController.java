package com.hivemind.skill;

import com.hivemind.common.ApiResponse;
import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 技能与经验接口：看得到"学到什么、谁验证过、够不够格推广"。 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillStore skillStore;
    private final ExperienceBus experienceBus;
    private final SkillShadowService shadowService;
    private final SkillRecallEvaluation recallEvaluation;
    private final SkillCurator skillCurator;
    private final HiveProperties properties;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillArtifact artifact : skillStore.allActive()) {
            SkillStats stats = skillStore.stats(artifact.skillId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skillId", artifact.skillId());
            row.put("version", artifact.version());
            row.put("title", artifact.title());
            row.put("trigger", artifact.trigger());
            row.put("procedure", artifact.procedure());
            row.put("versions", skillStore.versions(artifact.skillId()).size());
            row.put("uses", stats.uses());
            row.put("successes", stats.successes());
            row.put("fitness", stats.fitness());
            row.put("validatedByNodes", stats.validators());
            row.put("promotable", skillStore.promotable(artifact.skillId()));
            row.put("retired", skillStore.isRetired(artifact.skillId()));
            out.add(row);
        }
        return ApiResponse.ok(out);
    }

    /** 反馈某条经验的效果：这是"适应度"的输入来源。 */
    @PostMapping("/{skillId}/feedback")
    public ApiResponse<Map<String, Object>> feedback(@PathVariable String skillId,
                                                     @RequestParam String nodeId,
                                                     @RequestParam(defaultValue = "true") boolean success,
                                                     @RequestParam(defaultValue = "0") int version,
                                                     @RequestParam(defaultValue = "") String taskId) {
        SkillArtifact artifact = skillStore.active(skillId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "技能不存在：" + skillId));
        skillStore.recordUsage(new SkillUsage(skillId, version > 0 ? version : artifact.version(),
                nodeId, taskId, success));
        SkillStats stats = skillStore.stats(skillId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", skillId);
        body.put("uses", stats.uses());
        body.put("fitness", stats.fitness());
        body.put("promotable", skillStore.promotable(skillId));
        return ApiResponse.ok(body);
    }

    /** 手动蒸馏一条经验（也可由任务成功后的自动蒸馏产生）。 */
    @PostMapping("/distill")
    public ApiResponse<SkillArtifact> distill(@RequestBody DistillRequest request) {
        Optional<SkillArtifact> artifact = experienceBus.distill(request.input(), request.answer(),
                request.nodeId() == null ? "manual" : request.nodeId(), request.taskId());
        return artifact.map(ApiResponse::ok)
                .orElseThrow(() -> new BizException(ErrorCode.GATE_REJECTED, "蒸馏失败：模型不可用或输出无法解析"));
    }

    /** 够格推广的技能（跨节点验证 + 适应度 + 使用次数门槛），供 gossip 广播。 */
    @GetMapping("/promotable")
    public ApiResponse<List<SkillArtifact>> promotable() {
        return ApiResponse.ok(skillStore.promotableArtifacts());
    }

    /** A/B 影子对比：注入技能 vs 不注入，比较结果并回写适应度。 */
    @PostMapping("/shadow")
    public ApiResponse<SkillShadowService.ShadowReport> shadow(@RequestBody ShadowRequest request) {
        return ApiResponse.ok(shadowService.compare(request.input(), request.skillId(), request.version()));
    }

    /** 召回质量评估：在固定夹具上算 precision@k，改召回算法后可直接对比。 */
    @GetMapping("/eval")
    public ApiResponse<SkillRecallEvaluation.Report> evaluateRecall(@RequestParam(required = false) Integer topK) {
        return ApiResponse.ok(topK == null ? recallEvaluation.evaluate() : recallEvaluation.evaluate(topK));
    }

    /** 退役一条技能：立刻停止召回与广播（历史与统计保留，可恢复）。 */
    @PostMapping("/{skillId}/retire")
    public ApiResponse<Map<String, Object>> retire(@PathVariable String skillId,
                                                   @RequestParam(defaultValue = "人工退役") String reason) {
        SkillArtifact artifact = skillStore.active(skillId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "技能不存在：" + skillId));
        skillStore.retire(skillId, reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", artifact.skillId());
        body.put("retired", true);
        body.put("reason", reason);
        return ApiResponse.ok(body);
    }

    @PostMapping("/{skillId}/unretire")
    public ApiResponse<Map<String, Object>> unretire(@PathVariable String skillId) {
        skillStore.unretire(skillId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", skillId);
        body.put("retired", skillStore.isRetired(skillId));
        return ApiResponse.ok(body);
    }

    /** 策展视图：自动淘汰候选 + 已退役清单；?run=true 立即执行一次淘汰巡检。 */
    @GetMapping("/curation")
    public ApiResponse<Map<String, Object>> curation(@RequestParam(defaultValue = "false") boolean run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidates", skillStore.retireCandidates());
        body.put("retired", skillStore.retiredSkillIds());
        body.put("retireMinUses", properties.getSkill().getRetireMinUses());
        body.put("retireFitnessThreshold", properties.getSkill().getRetireFitnessThreshold());
        if (run) {
            body.put("retiredNow", skillCurator.curateNow());
            body.put("retired", skillStore.retiredSkillIds());
        }
        return ApiResponse.ok(body);
    }

    public record ShadowRequest(String input, String skillId, Integer version) {
    }

    public record DistillRequest(String input, String answer, String nodeId, String taskId) {
    }
}
