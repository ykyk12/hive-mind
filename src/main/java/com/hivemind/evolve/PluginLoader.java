package com.hivemind.evolve;

import com.hivemind.agent.Tool;
import com.hivemind.agent.ToolContext;
import com.hivemind.agent.ToolResult;
import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件加载器：契约校验 + 冒烟执行。
 *
 * 冒烟（smoke）是什么、不是什么，这里必须说清楚：
 *  - 是：能加载、能实例化、能按参数契约被调用并返回非空结果——挡住"编译通过但一调就崩"的插件；
 *  - 不是：完整的单元测试。真正的测试门禁（用 JUnit Platform Launcher 在隔离类加载器里跑候选测试）
 *    在 README 的路线图里，属于 M3 范围。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginLoader {

    private final HiveProperties properties;

    public record LoadResult(boolean success, Tool tool, String message, String loaderName) {
    }

    public LoadResult load(Path classesDir, String className) {
        try (IsolatedToolClassLoader loader = new IsolatedToolClassLoader(classesDir,
                PluginLoader.class.getClassLoader())) {
            Class<?> type = loader.loadClass(className);
            if (!Tool.class.isAssignableFrom(type)) {
                return new LoadResult(false, null, "类未实现 com.hivemind.agent.Tool", loader.toString());
            }
            Object instance = type.getDeclaredConstructor().newInstance();
            Tool tool = (Tool) instance;
            if (tool.name() == null || tool.name().isBlank()) {
                return new LoadResult(false, null, "tool.name() 为空", loader.toString());
            }
            if (tool.parameterSchema() == null) {
                return new LoadResult(false, null, "parameterSchema() 返回 null", loader.toString());
            }
            if (properties.getEvolve().isRequireSmokeCheck()) {
                SmokeResult smoke = smoke(tool);
                if (!smoke.ok()) {
                    return new LoadResult(false, null, "冒烟未通过：" + smoke.message(), loader.toString());
                }
                log.info("插件冒烟通过：{}（{}）", tool.name(), smoke.message());
            }
            return new LoadResult(true, tool, "加载并冒烟通过", loader.toString());
        } catch (Throwable t) {
            return new LoadResult(false, null, "加载失败：" + t.getClass().getSimpleName() + " " + t.getMessage(), null);
        }
    }

    public record SmokeResult(boolean ok, String message) {
    }

    private SmokeResult smoke(Tool tool) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (String key : tool.parameterSchema().keySet()) {
            args.put(key, "hive-smoke");
        }
        try {
            ToolResult result = tool.invoke(new ToolContext("smoke-node", "smoke-tenant", "smoke-task", true), args);
            if (result == null) {
                return new SmokeResult(false, "invoke 返回 null");
            }
            if (!result.success()) {
                return new SmokeResult(false, "invoke 返回失败：" + result.output());
            }
            if (result.output() == null || result.output().isBlank()) {
                return new SmokeResult(false, "invoke 输出为空");
            }
            return new SmokeResult(true, "输出=" + result.output());
        } catch (Throwable t) {
            return new SmokeResult(false, t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }
}
