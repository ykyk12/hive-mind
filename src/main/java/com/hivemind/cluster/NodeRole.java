package com.hivemind.cluster;

/**
 * 节点角色。**不是部署差异，而是运行期状态**：
 * 同一个 jar 启动后一律是 NEURON，心跳/租约超时后按 term 竞选成为 BRAIN。
 */
public enum NodeRole {
    /** 神经元：执行任务、上报经验 */
    NEURON,
    /** 大脑：调度任务、聚合经验、决定推广 */
    BRAIN
}
