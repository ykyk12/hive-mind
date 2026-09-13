package com.hivemind.skill;

import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能策展（自动淘汰）：定期把"用够了但适应度掉下去"的技能退役。
 *
 * 为什么必须有淘汰：只增不减的经验库会持续向上下文注入早已失效的做法，
 * 让模型在错误的方向上越来越自信。淘汰是"进化"的必要代价，不是可选项。
 * 退役只停止召回与广播，历史与统计全部保留，随时可 unretire 复盘。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCurator {

    private final SkillStore skillStore;
    private final HiveProperties properties;

    @Scheduled(fixedDelayString = "${hive.skill.curation-interval-seconds:300}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void curate() {
        try {
            int retired = curateNow();
            if (retired > 0) {
                log.info("技能策展完成：本次退役 {} 条", retired);
            }
        } catch (RuntimeException e) {
            log.warn("技能策展异常（已忽略）：{}", e.toString());
        }
    }

    /** 立即执行一次淘汰巡检，返回退役条数（供接口与测试调用）。 */
    public int curateNow() {
        if (properties.getSkill().getCurationIntervalSeconds() <= 0) {
            log.debug("自动淘汰已关闭（curation-interval-seconds<=0）");
            return 0;
        }
        List<String> candidates = skillStore.retireCandidates();
        for (String skillId : candidates) {
            skillStore.retire(skillId, "适应度低于阈值 "
                    + properties.getSkill().getRetireFitnessThreshold()
                    + " 且使用次数已达 " + properties.getSkill().getRetireMinUses());
        }
        return candidates.size();
    }
}
