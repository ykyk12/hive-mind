package com.hivemind.govern;

import com.hivemind.change.AuditEntry;
import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.change.GateDecision;
import com.hivemind.change.ReviewVerdict;
import com.hivemind.common.ApiResponse;
import com.hivemind.evolve.ApplyResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自改治理接口（全部需要 X-Hive-Admin-Key）。
 *
 * 演示顺序：
 *   1) POST /proposals/generate {"goal":"..."}      提议者产出提案
 *   2) POST /proposals/{id}/review {"reviewer":"..."} 换一个角色审核（同一角色会被拒）
 *   3) POST /proposals/{id}/gate                    门锁裁决
 *   4) 需要签名时 POST /proposals/{id}/sign          人工签名
 *   5) POST /proposals/{id}/apply                   隔离编译+冒烟+原子注册
 */
@Tag(name = "自改治理", description = "变更提案生命周期：提交/生成/审核/门锁/签名/应用/审计")
@RestController
@RequestMapping("/api/v1/governance")
@RequiredArgsConstructor
public class GovernanceController {

    private final GovernanceService governance;

    @Operation(summary = "提交变更提案")
    @PostMapping("/proposals")
    public ApiResponse<ChangeProposal> submit(@RequestBody SubmitRequest request) {
        return ApiResponse.ok(governance.submit(request.kind(), request.title(), request.rationale(),
                request.expectedBenefit(), request.declaredRisk(), request.proposer(), request.payload()));
    }

    @Operation(summary = "由目标自动生成提案")
    @PostMapping("/proposals/generate")
    public ApiResponse<ChangeProposal> generate(@RequestBody GenerateRequest request) {
        return ApiResponse.ok(governance.generate(request.goal(), request.proposer()));
    }

    @Operation(summary = "另一角色审核提案")
    @PostMapping("/proposals/{proposalId}/review")
    public ApiResponse<ReviewVerdict> review(@PathVariable String proposalId,
                                             @RequestBody ReviewRequest request) {
        return ApiResponse.ok(governance.review(proposalId, request.reviewer()));
    }

    @Operation(summary = "门锁裁决")
    @PostMapping("/proposals/{proposalId}/gate")
    public ApiResponse<GateDecision> gate(@PathVariable String proposalId) {
        return ApiResponse.ok(governance.gate(proposalId));
    }

    @Operation(summary = "人工签名放行")
    @PostMapping("/proposals/{proposalId}/sign")
    public ApiResponse<ChangeProposal> sign(@PathVariable String proposalId,
                                            @RequestBody SignRequest request) {
        return ApiResponse.ok(governance.sign(proposalId, request.signer(), request.note()));
    }

    @Operation(summary = "应用提案（隔离编译+冒烟+原子注册）")
    @PostMapping("/proposals/{proposalId}/apply")
    public ApiResponse<ApplyResult> apply(@PathVariable String proposalId) {
        return ApiResponse.ok(governance.apply(proposalId));
    }

    @Operation(summary = "列出全部提案")
    @GetMapping("/proposals")
    public ApiResponse<List<ChangeProposal>> list() {
        return ApiResponse.ok(governance.all());
    }

    @Operation(summary = "提案详情")
    @GetMapping("/proposals/{proposalId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String proposalId) {
        return ApiResponse.ok(governance.detail(proposalId));
    }

    @Operation(summary = "审计轨迹")
    @GetMapping("/audit")
    public ApiResponse<List<AuditEntry>> audit(@RequestParam(required = false) String proposalId) {
        return ApiResponse.ok(governance.audit(proposalId));
    }

    @Operation(summary = "治理策略视图")
    @GetMapping("/policies")
    public ApiResponse<Map<String, Object>> policies() {
        return ApiResponse.ok(governance.policies());
    }

    public record SubmitRequest(ChangeKind kind,
                                String title,
                                String rationale,
                                String expectedBenefit,
                                Integer declaredRisk,
                                String proposer,
                                Map<String, Object> payload) {
    }

    public record GenerateRequest(String goal, String proposer) {
    }

    public record ReviewRequest(String reviewer) {
    }

    public record SignRequest(String signer, String note) {
    }
}
