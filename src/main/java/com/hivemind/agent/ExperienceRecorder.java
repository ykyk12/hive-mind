package com.hivemind.agent;

/** 经验记录端口：任务结束后把轨迹交给记忆层（实现：skill.ExperienceBus）。 */
public interface ExperienceRecorder {

    void record(TaskTrace trace);
}
