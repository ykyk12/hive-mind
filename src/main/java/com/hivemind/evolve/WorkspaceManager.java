package com.hivemind.evolve;

import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 制品工作区：所有"自改产物"都先落到 staging 目录，通过全部门禁后才进制品库。
 *
 * 关键点：**永远不在运行目录原地改**。运行中的节点只持有类引用，
 * 候选代码先在影子目录里编译/加载/冒烟，失败就地丢弃，不会污染正在服务的实例。
 */
@Slf4j
@Component
public class WorkspaceManager {

    private final Path root;

    public WorkspaceManager(HiveProperties properties) {
        this.root = Path.of(properties.getEvolve().getRoot()).toAbsolutePath().normalize();
        log.info("制品工作区：{}", root);
    }

    public Path root() {
        return root;
    }

    public Path staging(String proposalId) throws IOException {
        Path dir = root.resolve("workspace").resolve(proposalId);
        Files.createDirectories(dir);
        return dir;
    }

    public Path classesDir(String proposalId) throws IOException {
        Path dir = staging(proposalId).resolve("classes");
        Files.createDirectories(dir);
        return dir;
    }

    /** 按包路径写源码文件，返回文件路径。 */
    public Path writeSource(String proposalId, String packageName, String className, String source) throws IOException {
        Path dir = staging(proposalId).resolve("src").resolve(packageName.replace('.', '/'));
        Files.createDirectories(dir);
        Path file = dir.resolve(className + ".java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        log.info("候选源码落盘：{}", file);
        return file;
    }

    /** 冒烟通过后把源码与字节码一起归档，形成可回滚的制品目录。 */
    public Path archive(String proposalId, String name, int version, Path sourceFile) throws IOException {
        Path dir = root.resolve("artifacts").resolve(name).resolve("v" + version);
        Files.createDirectories(dir);
        copyTree(staging(proposalId).resolve("classes"), dir.resolve("classes"));
        Files.createDirectories(dir.resolve("src"));
        Files.copy(sourceFile, dir.resolve("src").resolve(sourceFile.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
        log.info("制品归档：{}", dir);
        return dir;
    }

    public Path artifactsRoot() {
        return root.resolve("artifacts");
    }

    /** 清掉某个提案的 staging（成功归档后调用，避免临时目录堆积）。 */
    public void discardStaging(String proposalId) {
        Path dir = root.resolve("workspace").resolve(proposalId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("清理 staging 失败：{}", path);
                }
            });
        } catch (IOException e) {
            log.warn("清理 staging 目录失败：{}", e.getMessage());
        }
    }

    private void copyTree(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            List<Path> paths = walk.toList();
            for (Path path : paths) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
