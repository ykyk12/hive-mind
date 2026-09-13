package com.hivemind.evolve;

import com.hivemind.agent.Tool;
import com.hivemind.agent.ToolRegistry;
import com.hivemind.change.AuditTrail;
import com.hivemind.change.ChangeKind;
import com.hivemind.change.ChangeProposal;
import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.config.HiveProperties;
import com.hivemind.skill.SkillArtifact;
import com.hivemind.skill.SkillStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 进化流水线：把"通过门禁的提案"真正落到系统里。
 *
 * 流程（PLUGIN 为例）：
 *   落盘 → 隔离编译 → 隔离加载 → 冒烟 → 归档制品 → 原子注册（ToolRegistry 指针切换）
 * 任何一步失败都停在原地，运行中的实例不受影响；失败原因写进审计，便于人看。
 *
 * 内核源码（KERNEL）**不在此自动应用**：它需要走正常代码评审与发布流程，这里是显式拒绝。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionPipeline {

    private final HiveProperties properties;
    private final ToolRegistry toolRegistry;
    private final SkillStore skillStore;
    private final WorkspaceManager workspace;
    private final JavaSourceCompiler compiler;
    private final CandidateTestRunner testRunner;
    private final PluginLoader pluginLoader;
    private final ArtifactStore artifactStore;
    private final ConfigOverlay configOverlay;
    private final AuditTrail audit;

    public ApplyResult apply(ChangeProposal proposal) {
        long start = System.nanoTime();
        List<String> steps = new ArrayList<>();
        try {
            return switch (proposal.kind()) {
                case PLUGIN -> applyPlugin(proposal, steps, start);
                case SKILL -> applySkill(proposal, steps, start);
                case CONFIG -> applyConfig(proposal, steps, start);
                case KERNEL -> new ApplyResult(proposal.proposalId(), false, null,
                        "内核源码变更不在自动应用范围：请人工走代码评审与发布流程",
                        List.of("kinds=KERNEL 已被门禁标记为必须人工签名"),
                        elapsed(start));
            };
        } catch (Exception e) {
            log.error("进化流水线执行异常 proposal={}", proposal.proposalId(), e);
            audit.record("APPLY_FAILED", proposal.proposalId(), "evolution-pipeline", e.toString());
            return new ApplyResult(proposal.proposalId(), false, null,
                    "执行失败：" + e.getClass().getSimpleName() + " " + e.getMessage(), steps, elapsed(start));
        }
    }

    private ApplyResult applyPlugin(ChangeProposal proposal, List<String> steps, long start) throws Exception {
        String className = proposal.payloadText("className");
        String packageName = proposal.payloadText("packageName");
        String source = proposal.payloadText("source");
        String testClassName = proposal.payloadText("testClassName");
        String testSource = proposal.payloadText("testSource");

        if (properties.getEvolve().isRequireTests() && (testClassName.isBlank() || testSource.isBlank())) {
            audit.record("APPLY_REJECTED", proposal.proposalId(), "evolution-pipeline",
                    "测试门禁要求候选插件自带单元测试，但未提供 testClassName/testSource");
            return new ApplyResult(proposal.proposalId(), false, null,
                    "测试门禁未满足：候选插件必须自带单元测试（testClassName + testSource）",
                    append(steps, List.of("拒绝：缺少候选单元测试")), elapsed(start));
        }

        steps.add("落盘候选源码：" + packageName + "." + className);
        Path sourceFile = workspace.writeSource(proposal.proposalId(), packageName, className, source);

        List<Path> sourcesToCompile = new ArrayList<>();
        sourcesToCompile.add(sourceFile);
        if (!testSource.isBlank()) {
            Path testFile = workspace.writeSource(proposal.proposalId(), packageName, testClassName, testSource);
            sourcesToCompile.add(testFile);
            steps.add("落盘候选测试：" + packageName + "." + testClassName);
        }

        Path classesDir = workspace.classesDir(proposal.proposalId());
        JavaSourceCompiler.CompileResult compiled = compiler.compileAll(sourcesToCompile, classesDir);
        steps.add("隔离编译：" + (compiled.success() ? "通过" : "失败") + "（源文件 " + sourcesToCompile.size() + " 个）");
        if (!compiled.success()) {
            audit.record("APPLY_REJECTED", proposal.proposalId(), "evolution-pipeline",
                    "编译失败：" + compiled.diagnostics());
            return new ApplyResult(proposal.proposalId(), false, null,
                    "编译未通过，候选代码已丢弃", append(steps, compiled.diagnostics()), elapsed(start));
        }

        if (!testClassName.isBlank()) {
            CandidateTestRunner.TestReport report = testRunner.run(classesDir, packageName + "." + testClassName);
            steps.add("候选单元测试：" + report.message());
            if (!report.success()) {
                List<String> details = new ArrayList<>(report.failures());
                if (details.isEmpty()) {
                    details.add(report.message());
                }
                audit.record("APPLY_REJECTED", proposal.proposalId(), "evolution-pipeline",
                        "测试门禁未通过：" + report.message() + " " + report.failures());
                return new ApplyResult(proposal.proposalId(), false, null,
                        "测试门禁未通过：" + report.message(), append(steps, details), elapsed(start));
            }
        }

        PluginLoader.LoadResult loaded = pluginLoader.load(classesDir, packageName + "." + className);
        steps.add("隔离加载与冒烟：" + (loaded.success() ? "通过" : "失败") + "（" + loaded.message() + "）");
        if (!loaded.success()) {
            audit.record("APPLY_REJECTED", proposal.proposalId(), "evolution-pipeline", loaded.message());
            return new ApplyResult(proposal.proposalId(), false, null, loaded.message(), steps, elapsed(start));
        }

        Tool tool = loaded.tool();
        int version = artifactStore.nextVersion(tool.name());
        Path archived = workspace.archive(proposal.proposalId(), tool.name(), version, sourceFile);
        steps.add("制品归档：" + archived);
        toolRegistry.register(tool, "plugin@" + tool.name() + "-v" + version);
        ArtifactStore.ArtifactRecord record = artifactStore.record(tool.name(), tool.name() + "-v" + version,
                ChangeKind.PLUGIN, archived.toString());
        workspace.discardStaging(proposal.proposalId());
        steps.add("原子注册生效：工具 " + tool.name() + "，制品 " + record.artifactId());
        audit.record("APPLIED", proposal.proposalId(), "evolution-pipeline",
                "插件生效：" + tool.name() + "（制品 " + record.artifactId() + "，测试门禁已通过）");
        return new ApplyResult(proposal.proposalId(), true, record.artifactId(),
                "插件 " + tool.name() + " 已生效（测试通过 + 可回滚）", steps, elapsed(start));
    }

    private ApplyResult applySkill(ChangeProposal proposal, List<String> steps, long start) {
        String trigger = proposal.payloadText("trigger");
        String procedure = proposal.payloadText("procedure");
        String title = proposal.payloadText("title").isBlank() ? proposal.title() : proposal.payloadText("title");
        Set<String> tags = new LinkedHashSet<>();
        Object rawTags = proposal.payload() == null ? null : proposal.payload().get("tags");
        if (rawTags instanceof List<?> list) {
            list.forEach(tag -> tags.add(String.valueOf(tag)));
        }
        SkillArtifact artifact = skillStore.publish(new SkillArtifact(
                "skill-" + proposal.proposalId(), 0, title, trigger, procedure, Set.copyOf(tags),
                Set.of(proposal.proposer()), proposal.proposer(), System.currentTimeMillis()));
        steps.add("技能入库：" + artifact.ref());
        ArtifactStore.ArtifactRecord record = artifactStore.record(artifact.skillId(), artifact.ref(),
                ChangeKind.SKILL, "memory://skills/" + artifact.ref());
        audit.record("APPLIED", proposal.proposalId(), "evolution-pipeline", "技能生效：" + artifact.ref());
        return new ApplyResult(proposal.proposalId(), true, record.artifactId(),
                "技能 " + artifact.ref() + " 已入库", steps, elapsed(start));
    }

    private ApplyResult applyConfig(ChangeProposal proposal, List<String> steps, long start) {
        String key = proposal.payloadText("key");
        String value = proposal.payloadText("value");
        ConfigOverlay.Change change = configOverlay.apply(key, value);
        steps.add("配置热改：" + change.key() + " " + change.oldValue() + " → " + change.newValue());
        ArtifactStore.ArtifactRecord record = artifactStore.record(key, key + "=" + value,
                ChangeKind.CONFIG, "memory://config/" + key);
        audit.record("APPLIED", proposal.proposalId(), "evolution-pipeline", "配置生效：" + key + "=" + value);
        return new ApplyResult(proposal.proposalId(), true, record.artifactId(),
                "配置 " + key + " 已热改并保留旧值（可回滚）", steps, elapsed(start));
    }

    /**
     * 回滚：把制品指针退回上一个版本。
     *
     * 配置类变更走 ConfigOverlay 记录的旧值（不要求存在第二个制品记录）；
     * 插件/技能类变更逐版本回退，必须真的有更早版本可退，否则明确报冲突而不是假装成功。
     */
    public ApplyResult rollback(String name) {
        long start = System.nanoTime();
        List<String> steps = new ArrayList<>();
        ArtifactStore.ArtifactRecord active = artifactStore.active(name)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "没有生效中的制品：" + name));

        switch (active.kind()) {
            case CONFIG -> {
                ConfigOverlay.Change change = configOverlay.restore(name);
                steps.add("配置回滚：" + change.key() + " " + change.oldValue() + " → " + change.newValue());
                audit.record("ROLLBACK", name, "evolution-pipeline", "配置回滚 " + name);
                return new ApplyResult(name, true, active.artifactId(),
                        "配置 " + name + " 已恢复到热改前的值", steps, elapsed(start));
            }
            case PLUGIN -> {
                ArtifactStore.ArtifactRecord previous = artifactStore.previousOf(name)
                        .orElseThrow(() -> new BizException(ErrorCode.CONFLICT,
                                "制品 " + name + " 没有更早版本可回退（当前 v" + active.version() + "）"));
                Tool tool = toolRegistry.rollback(name);
                artifactStore.activate(name, previous);
                steps.add("工具注册表指针回退：当前生效 " + tool.name() + "（制品 " + previous.artifactId() + "）");
                audit.record("ROLLBACK", name, "evolution-pipeline",
                        "回退到 " + previous.artifactId() + "（原生效 " + active.artifactId() + "）");
                return new ApplyResult(name, true, previous.artifactId(),
                        "已回退到 " + previous.artifactId(), steps, elapsed(start));
            }
            case SKILL -> {
                ArtifactStore.ArtifactRecord previous = artifactStore.previousOf(name)
                        .orElseThrow(() -> new BizException(ErrorCode.CONFLICT,
                                "技能制品 " + name + " 没有更早版本可回退"));
                artifactStore.activate(name, previous);
                steps.add("技能制品指针回退到 " + previous.artifactId()
                        + "（技能库内容按版本指针读取，见 README 已知边界）");
                audit.record("ROLLBACK", name, "evolution-pipeline", "技能回退到 " + previous.artifactId());
                return new ApplyResult(name, true, previous.artifactId(),
                        "已回退到 " + previous.artifactId(), steps, elapsed(start));
            }
            default -> {
                return new ApplyResult(name, false, null,
                        "该制品类型不支持自动回滚：" + active.kind(), steps, elapsed(start));
            }
        }
    }

    public List<ArtifactStore.ArtifactRecord> history() {
        return artifactStore.history();
    }

    public List<ArtifactStore.ArtifactRecord> activeArtifacts() {
        return artifactStore.activeArtifacts();
    }

    public Map<String, String> hotConfigValues() {
        return configOverlay.currentValues();
    }

    public String workspaceRoot() {
        return properties.getEvolve().getRoot();
    }

    private List<String> append(List<String> steps, List<String> extra) {
        List<String> out = new ArrayList<>(steps);
        out.addAll(extra);
        return out;
    }

    private long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
