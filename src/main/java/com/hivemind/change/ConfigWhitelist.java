package com.hivemind.change;

import java.util.Set;

/**
 * 允许热改的配置键白名单。
 *
 * 为什么要有白名单：如果"自改"能改任意配置，它迟早会把自己改成起不来的样子
 * （例如把心跳周期改到 1 毫秒、把最大步数改成 0）。能热改的必须是可以被明确枚举、且改坏了也能回滚的那部分。
 */
public final class ConfigWhitelist {

    public static final String AGENT_MAX_STEPS = "agent.max-steps";
    public static final String AGENT_MAX_INJECTED_SKILLS = "agent.max-injected-skills";
    public static final String SKILL_PROMOTION_MIN_NODES = "skill.promotion-min-nodes";
    public static final String GOVERNANCE_MAX_RISK_SCORE = "governance.max-risk-score";

    public static final Set<String> KEYS = Set.of(
            AGENT_MAX_STEPS,
            AGENT_MAX_INJECTED_SKILLS,
            SKILL_PROMOTION_MIN_NODES,
            GOVERNANCE_MAX_RISK_SCORE);

    private ConfigWhitelist() {
    }

    public static boolean allowed(String key) {
        return key != null && KEYS.contains(key);
    }
}
