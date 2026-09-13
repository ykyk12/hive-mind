package com.hivemind.evolve;

import com.hivemind.change.ConfigWhitelist;
import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置热改覆盖层：只允许改动白名单里的键，并且记住旧值以便回滚。
 *
 * 为什么不干脆让自改改任意配置：能改任意配置的系统，迟早会把自己改成起不来的样子，
 * 而且这种故障往往在重启后才暴露，排查成本极高。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigOverlay {

    public record Change(String key, String oldValue, String newValue) {
    }

    private final HiveProperties properties;
    private final Map<String, String> previousValues = new ConcurrentHashMap<>();
    private final Map<String, String> currentValues = new ConcurrentHashMap<>();

    public Change apply(String key, String value) {
        if (!ConfigWhitelist.allowed(key)) {
            throw new BizException(ErrorCode.GATE_REJECTED, "配置键不在热改白名单内：" + key);
        }
        String old = read(key);
        set(key, value);
        previousValues.putIfAbsent(key, old);
        currentValues.put(key, value);
        log.info("配置热改生效：{} {} → {}", key, old, value);
        return new Change(key, old, value);
    }

    /** 回滚到该键第一次被热改前的值。 */
    public Change restore(String key) {
        String old = previousValues.get(key);
        if (old == null) {
            throw new BizException(ErrorCode.CONFLICT, "配置 " + key + " 没有可回滚的热改记录");
        }
        String current = read(key);
        set(key, old);
        previousValues.remove(key);
        currentValues.remove(key);
        log.warn("配置回滚：{} {} → {}", key, current, old);
        return new Change(key, current, old);
    }

    public Map<String, String> currentValues() {
        return Map.copyOf(currentValues);
    }

    private String read(String key) {
        return switch (key) {
            case ConfigWhitelist.AGENT_MAX_STEPS -> String.valueOf(properties.getAgent().getMaxSteps());
            case ConfigWhitelist.AGENT_MAX_INJECTED_SKILLS -> String.valueOf(properties.getAgent().getMaxInjectedSkills());
            case ConfigWhitelist.SKILL_PROMOTION_MIN_NODES -> String.valueOf(properties.getSkill().getPromotionMinNodes());
            case ConfigWhitelist.GOVERNANCE_MAX_RISK_SCORE -> String.valueOf(properties.getGovernance().getMaxRiskScore());
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "配置键不在白名单内：" + key);
        };
    }

    private void set(String key, String value) {
        try {
            int number = Integer.parseInt(value.trim());
            switch (key) {
                case ConfigWhitelist.AGENT_MAX_STEPS -> properties.getAgent().setMaxSteps(number);
                case ConfigWhitelist.AGENT_MAX_INJECTED_SKILLS -> properties.getAgent().setMaxInjectedSkills(number);
                case ConfigWhitelist.SKILL_PROMOTION_MIN_NODES -> properties.getSkill().setPromotionMinNodes(number);
                case ConfigWhitelist.GOVERNANCE_MAX_RISK_SCORE -> properties.getGovernance().setMaxRiskScore(number);
                default -> throw new BizException(ErrorCode.BAD_REQUEST, "配置键不在白名单内：" + key);
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "配置 " + key + " 需要一个整数，收到：" + value);
        }
    }
}
