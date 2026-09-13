package com.hivemind.agent;

import java.util.List;

/**
 * 技能顾问端口：Agent 循环只管"要几条建议"，不关心记忆是怎么存/怎么进化的。
 * 实现由 skill 包提供（SkillStore），这样 agent 包不依赖记忆实现，也没有循环依赖。
 */
public interface SkillAdvisor {

    /** 按任务输入召回最相关的技能片段，按适应度排序。 */
    List<SkillHint> hintsFor(String taskInput, int max);

    record SkillHint(String skillId, int version, String title, String procedure, double fitness) {
    }
}
