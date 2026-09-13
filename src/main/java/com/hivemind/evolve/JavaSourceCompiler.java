package com.hivemind.evolve;

import com.hivemind.agent.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 隔离编译：用 JDK 自带的 javax.tools 编译候选源码。
 *
 * 两个实践坑（都在这里处理了）：
 * 1) 必须有 JDK 而不是 JRE：ToolProvider.getSystemJavaCompiler() 在纯 JRE 下返回 null，
 *    所以这里显式判空并给出可读原因，而不是抛 NPE；
 * 2) 类路径必须显式拼装：Surefire 等环境里 java.class.path 可能只有 booter jar，
 *    所以额外用"自身类的 CodeSource"兜底，保证 com.hivemind.agent.* 能被解析。
 */
@Slf4j
@Component
public class JavaSourceCompiler {

    public record CompileResult(boolean success, List<String> diagnostics, Path classesDir) {
    }

    public CompileResult compile(Path sourceFile, Path classesDir) {
        return compileAll(List.of(sourceFile), classesDir);
    }

    /** 一次编译多个源文件：插件类与它的测试类必须一起编译，否则测试看不到被测类。 */
    public CompileResult compileAll(List<Path> sourceFiles, Path classesDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(false,
                    List.of("当前 JVM 没有 JavaCompiler：请用 JDK（而非 JRE）运行"), classesDir);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<File> files = new ArrayList<>();
            for (Path sourceFile : sourceFiles) {
                files.add(sourceFile.toFile());
            }
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files);
            List<String> options = new ArrayList<>(List.of("-d", classesDir.toString(), "-proc:none"));
            String classpath = classpath();
            if (!classpath.isBlank()) {
                options.add("-classpath");
                options.add(classpath);
            }
            boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            List<String> messages = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                messages.add(diagnostic.getKind() + " 行 " + diagnostic.getLineNumber() + "：" + diagnostic.getMessage(null));
            }
            log.info("隔离编译结果 success={} 文件={} 诊断={} 条", success, sourceFiles.size(), messages.size());
            return new CompileResult(success, List.copyOf(messages), classesDir);
        } catch (IOException e) {
            return new CompileResult(false, List.of("编译过程 IO 异常：" + e.getMessage()), classesDir);
        }
    }

    /** 拼装编译类路径：java.class.path ∪ 自身类所在位置（兜底）∪ 已编译产物目录。 */
    String classpath(Path... extra) {
        Set<String> entries = new LinkedHashSet<>();
        String runtimeClasspath = System.getProperty("java.class.path", "");
        for (String part : runtimeClasspath.split(Pattern.quote(File.pathSeparator))) {
            if (!part.isBlank()) {
                entries.add(part);
            }
        }
        entries.addAll(codeSourceLocations());
        for (Path path : extra) {
            if (path != null) {
                entries.add(path.toString());
            }
        }
        entries.removeIf(entry -> !Files.exists(Path.of(entry)));
        return String.join(File.pathSeparator, entries);
    }

    private List<String> codeSourceLocations() {
        List<String> locations = new ArrayList<>();
        locations.addAll(codeSourceOf(Tool.class));
        locations.addAll(codeSourceOf(JavaSourceCompiler.class));
        // 候选测试要 import JUnit 注解：把 JUnit 所在位置也加入编译类路径（按需探测，缺失不影响主流程）
        for (String hint : List.of("org.junit.jupiter.api.Test",
                "org.junit.platform.launcher.core.LauncherFactory")) {
            try {
                locations.addAll(codeSourceOf(Class.forName(hint, false, JavaSourceCompiler.class.getClassLoader())));
            } catch (Throwable ignored) {
                log.debug("编译类路径提示类不可用（跳过）：{}", hint);
            }
        }
        return locations;
    }

    private List<String> codeSourceOf(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                return List.of(Path.of(source.getLocation().toURI()).toString());
            }
        } catch (Exception e) {
            log.debug("获取 {} 的 CodeSource 失败：{}", type.getSimpleName(), e.getMessage());
        }
        return List.of();
    }
}
