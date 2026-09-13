package com.hivemind.skill;

import java.util.Set;

/**
 * 技能制品：不可变、可版本化、可反熵传播。
 *
 * 为什么必须是"制品"而不是"记忆条目"：只有不可变 + 带版本，才能回答
 * "这条经验是谁在哪个版本、哪几个节点上验证有效的"，也才能回滚。
 */
public record SkillArtifact(String skillId,
                            int version,
                            String title,
                            String trigger,
                            String procedure,
                            Set<String> tags,
                            Set<String> sourceNodes,
                            String originNodeId,
                            long createdAtMillis) {

    public SkillArtifact withSourceNode(String nodeId) {
        Set<String> nodes = new java.util.LinkedHashSet<>(sourceNodes);
        nodes.add(nodeId);
        return new SkillArtifact(skillId, version, title, trigger, procedure, tags,
                Set.copyOf(nodes), originNodeId, createdAtMillis);
    }

    public String ref() {
        return skillId + "@" + version;
    }
}
