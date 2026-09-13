package com.hivemind.change;

/**
 * 变更类型与它的 payload 契约。
 *
 * SKILL  : {"title","trigger","procedure","tags":[]}         —— 只影响"经验"，随时可回滚
 * CONFIG : {"key","value"}                                    —— 只允许白名单配置键热改
 * PLUGIN : {"className","packageName","source"}                —— 新增工具类，需隔离编译 + 冒烟
 * KERNEL : {"path","patch"}                                    —— 改内核源码，永不自动放行
 */
public enum ChangeKind {
    SKILL,
    CONFIG,
    PLUGIN,
    KERNEL
}
