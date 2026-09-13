package com.hivemind.skill;

/** 技能使用上报：哪个节点、哪次任务、用了哪个技能版本、成没成。 */
public record SkillUsage(String skillId, int version, String nodeId, String taskId, boolean success) {
}
