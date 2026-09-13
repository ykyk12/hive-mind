package com.hivemind.agent;

import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：内置工具 + 进化产物（插件）共用一张表。
 *
 * 同名工具的替换是"版本指针切换"语义：保留旧版本引用以便回滚，新调用一律走新版本。
 */
@Slf4j
@Component
public class ToolRegistry {

    /** 一个工具条目的历史版本（最新在前）。 */
    public record Registration(String name, Tool tool, String source, long registeredAtMillis) {
    }

    private final Map<String, List<Registration>> byName = new LinkedHashMap<>();

    public synchronized void register(Tool tool, String source) {
        List<Registration> versions = byName.computeIfAbsent(tool.name(), k -> new ArrayList<>());
        Registration registration = new Registration(tool.name(), tool, source, System.currentTimeMillis());
        versions.add(0, registration);
        log.info("工具注册：name={} source={} risk={}（该工具历史版本 {} 个）",
                tool.name(), source, tool.risk(), versions.size());
    }

    /** 回滚：把当前版本切回上一个历史版本。 */
    public synchronized Tool rollback(String name) {
        List<Registration> versions = byName.get(name);
        if (versions == null || versions.size() < 2) {
            throw new BizException(ErrorCode.CONFLICT, "工具 " + name + " 没有可回滚的历史版本");
        }
        Registration previous = versions.remove(0);
        log.warn("工具回滚：name={} 从 source={} 退回到 source={}", name, previous.source(), versions.get(0).source());
        return versions.get(0).tool();
    }

    public synchronized Optional<Tool> find(String name) {
        List<Registration> versions = byName.get(name);
        return versions == null || versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(0).tool());
    }

    public synchronized List<Tool> all() {
        return byName.values().stream()
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0).tool())
                .sorted(Comparator.comparing(Tool::name))
                .toList();
    }

    public synchronized List<Registration> versions(String name) {
        return List.copyOf(byName.getOrDefault(name, List.of()));
    }

    public synchronized int size() {
        return byName.size();
    }

    /** 工具目录：渲染进系统提示词，让模型知道能调什么。 */
    public synchronized String catalog() {
        List<Tool> tools = new ArrayList<>(all());
        if (tools.isEmpty()) {
            return "(当前节点没有可用工具)";
        }
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.name())
                    .append("（风险 ").append(tool.risk()).append("）：").append(tool.description())
                    .append(" 参数=").append(tool.parameterSchema())
                    .append('\n');
        }
        return sb.toString();
    }
}
