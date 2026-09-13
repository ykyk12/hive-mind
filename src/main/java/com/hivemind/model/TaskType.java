package com.hivemind.model;

/** 任务类型：路由按"任务类型 × 模型能力画像"打分，而不是写死哪个模型最好。 */
public enum TaskType {
    /** 开放式对话 */
    CHAT,
    /** 多步推理/规划 */
    REASONING,
    /** 代码生成与修改 */
    CODE,
    /** 内容摘要 */
    SUMMARIZE,
    /** 结构化抽取（要求 JSON 输出） */
    EXTRACT,
    /** 轻量分类/路由决策 */
    ROUTE
}
