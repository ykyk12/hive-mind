package com.hivemind.bootstrap;

import com.hivemind.config.HiveProperties;
import com.hivemind.skill.SkillArtifact;
import com.hivemind.skill.SkillStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 演示装配：让节点启动后立刻"有一点经验"，并打印可直接复制的演示命令。
 * 只在技能库为空时写入，避免每次重启都堆版本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoBootstrap {

    private final SkillStore skillStore;
    private final HiveProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (skillStore.size() > 0) {
            return;
        }
        String nodeId = properties.getNode().getId();
        skillStore.publish(new SkillArtifact("skill-demo-tool-first", 0,
                "先查事实再回答",
                "问题涉及具体数据、时间、外部状态",
                "1) 先判断能否用工具拿到事实；2) 能就拿事实再答；3) 拿不到就明确说不知道，不要编。",
                Set.of("demo", "grounding"), Set.of(nodeId), nodeId, System.currentTimeMillis()));
        skillStore.publish(new SkillArtifact("skill-demo-risk-gate", 0,
                "高危工具要先要审批",
                "需要控制设备、发请求、改系统状态的操作",
                "1) 识别工具风险等级；2) MEDIUM/HIGH 先请求审批令牌；3) 被拒时说明原因，不绕过。",
                Set.of("demo", "safety"), Set.of(nodeId), nodeId, System.currentTimeMillis()));

        log.info("演示技能已写入 2 条（技能库 {} 条）", skillStore.size());
        log.info("节点 {} 就绪。演示入口：GET /api/v1/cluster/state ；任务：POST /api/v1/tasks ；"
                + "模型编排：GET /api/v1/models ；治理：GET /api/v1/governance/policies（需 X-Hive-Admin-Key）",
                nodeId);
    }
}
