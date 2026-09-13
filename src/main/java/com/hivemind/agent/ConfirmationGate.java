package com.hivemind.agent;

import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风险确认门（会话内的门锁）：
 * LOW 直接放行；MEDIUM/HIGH 必须携带审批令牌，否则不执行并登记为待确认。
 *
 * 这是"执行前"的刹车；治理面的 Proposer/Reviewer/Governor 是"改代码前"的刹车，两者职责不同。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmationGate {

    private final HiveProperties properties;
    private final Map<String, PendingConfirmation> pending = new ConcurrentHashMap<>();

    public record PendingConfirmation(String taskId, String nodeId, String toolName, RiskLevel risk, long requestedAtMillis) {
    }

    /** 判断某个工具在这次调用中是否允许执行。 */
    public Decision check(String taskId, Tool tool, ToolContext context) {
        RiskLevel risk = tool.risk();
        if (risk == RiskLevel.LOW) {
            return new Decision(true, "LOW 风险，直接执行");
        }
        if (context.approved()) {
            return new Decision(true, risk + " 风险，已带审批令牌");
        }
        PendingConfirmation confirmation = new PendingConfirmation(
                taskId, context.nodeId(), tool.name(), risk, System.currentTimeMillis());
        pending.put(key(taskId, tool.name()), confirmation);
        log.warn("工具调用被风险门拦下：task={} tool={} risk={}", taskId, tool.name(), risk);
        return new Decision(false, risk + " 风险工具 " + tool.name() + " 需要审批令牌（approve=true）");
    }

    public Map<String, PendingConfirmation> pending() {
        return Map.copyOf(pending);
    }

    public java.util.Optional<PendingConfirmation> find(String taskId, String toolName) {
        return java.util.Optional.ofNullable(pending.get(key(taskId, toolName)));
    }

    public void clear(String taskId, String toolName) {
        pending.remove(key(taskId, toolName));
    }

    private String key(String taskId, String toolName) {
        return taskId + "::" + toolName;
    }

    public record Decision(boolean allowed, String reason) {
    }
}
