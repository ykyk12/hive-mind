package com.hivemind.skill;

import com.hivemind.agent.SkillAdvisor;
import com.hivemind.common.BizException;
import com.hivemind.common.ErrorCode;
import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能库：版本化存储 + 适应度统计 + 召回 + 推广判定。
 *
 * 三条硬规则（对应"蜂巢别自己把自己搞坏"）：
 * 1) 版本不可变：任何修改都产生新版本，历史版本保留，便于回滚与追溯；
 * 2) 单节点自嗨的经验不许扩散：只有 promotionMinNodes 个不同节点验证过才可推广；
 * 3) 召回按"相关度 × 适应度"排序，而且只在输入与触发条件确实相关时才注入。
 */
@Slf4j
@Component
public class SkillStore implements SkillAdvisor {

    private final Map<String, List<SkillArtifact>> versions = new ConcurrentHashMap<>();
    private final Map<String, Integer> activeVersions = new ConcurrentHashMap<>();
    private final Map<String, SkillStats> statsBySkill = new ConcurrentHashMap<>();
    /** 退役技能：不再被召回、不再参与推广、不再广播（但历史与统计保留，便于事后复盘）。 */
    private final Set<String> retiredSkills = ConcurrentHashMap.newKeySet();
    private final HiveProperties properties;

    public SkillStore(HiveProperties properties) {
        this.properties = properties;
    }

    /** 发布技能版本；传入的 version<=0 表示自动递增。 */
    public synchronized SkillArtifact publish(SkillArtifact artifact) {
        List<SkillArtifact> list = versions.computeIfAbsent(artifact.skillId(), k -> new ArrayList<>());
        int nextVersion = list.stream().mapToInt(SkillArtifact::version).max().orElse(0) + 1;
        int version = artifact.version() > 0 ? artifact.version() : nextVersion;
        SkillArtifact stored = new SkillArtifact(artifact.skillId(), version, artifact.title(), artifact.trigger(),
                artifact.procedure(), artifact.tags(), artifact.sourceNodes(), artifact.originNodeId(),
                artifact.createdAtMillis() == 0 ? System.currentTimeMillis() : artifact.createdAtMillis());
        list.add(0, stored);
        activeVersions.put(stored.skillId(), version);
        statsBySkill.computeIfAbsent(stored.skillId(), k -> new SkillStats());
        log.info("技能发布：{}（标题={}，来源节点={}）", stored.ref(), stored.title(), stored.sourceNodes());
        return stored;
    }

    /**
     * 读取当前生效版本。注意：versions 的 value 是普通 ArrayList，publish/recordUsage 会原地修改它，
     * 因此读方法必须与写方法共用同一把 this 锁，否则并发 publish 时会抛 ConcurrentModificationException 或读到撕裂状态。
     */
    public synchronized Optional<SkillArtifact> active(String skillId) {
        Integer version = activeVersions.get(skillId);
        if (version == null) {
            return Optional.empty();
        }
        return versions.getOrDefault(skillId, List.of()).stream()
                .filter(a -> a.version() == version)
                .findFirst();
    }

    public synchronized List<SkillArtifact> versions(String skillId) {
        return List.copyOf(versions.getOrDefault(skillId, List.of()));
    }

    public synchronized List<SkillArtifact> allActive() {
        List<SkillArtifact> out = new ArrayList<>();
        for (String skillId : versions.keySet()) {
            active(skillId).ifPresent(out::add);
        }
        out.sort(Comparator.comparing(SkillArtifact::skillId));
        return out;
    }

    public SkillStats stats(String skillId) {
        return statsBySkill.computeIfAbsent(skillId, k -> new SkillStats());
    }

    /** 使用上报（本节点或远端节点都走这里）。 */
    public synchronized void recordUsage(SkillUsage usage) {
        stats(usage.skillId()).record(usage.nodeId(), usage.success());
        List<SkillArtifact> list = versions.get(usage.skillId());
        if (list == null || usage.nodeId() == null || usage.nodeId().isBlank()) {
            return;
        }
        // 普通 for 循环 + 索引替换：不要在流式遍历里改同一个 List（会抛 ConcurrentModificationException）
        for (int i = 0; i < list.size(); i++) {
            SkillArtifact artifact = list.get(i);
            if (artifact.version() == usage.version() && !artifact.sourceNodes().contains(usage.nodeId())) {
                list.set(i, artifact.withSourceNode(usage.nodeId()));
                return;
            }
        }
    }

    /** 远端节点验证过：计数校验节点，但不改变内容（内容传播走 upsertFromPeer）。 */
    public void recordRemoteValidation(String skillId, String nodeId) {
        stats(skillId).recordValidation(nodeId);
    }

    /** 反熵接收：同伴推来的技能版本入库（版本已存在则只补验证节点）。 */
    public synchronized SkillArtifact upsertFromPeer(SkillArtifact artifact, String peerNodeId) {
        Optional<SkillArtifact> existing = versions.getOrDefault(artifact.skillId(), List.of()).stream()
                .filter(a -> a.version() == artifact.version())
                .findFirst();
        if (existing.isPresent()) {
            recordRemoteValidation(artifact.skillId(), peerNodeId);
            return existing.get();
        }
        SkillArtifact stored = publish(artifact.withSourceNode(peerNodeId));
        recordRemoteValidation(stored.skillId(), peerNodeId);
        log.info("接收同伴技能：{} 来自节点 {}", stored.ref(), peerNodeId);
        return stored;
    }

    /** 是否允许推广到全网：跨节点验证 + 适应度 + 使用次数三重门槛，且未被退役。 */
    public boolean promotable(String skillId) {
        if (isRetired(skillId)) {
            return false;
        }
        HiveProperties.Skill config = properties.getSkill();
        SkillStats stats = stats(skillId);
        return stats.validators().size() >= config.getPromotionMinNodes()
                && stats.fitness() >= config.getPromotionMinFitness()
                && stats.uses() >= config.getPromotionMinUses();
    }

    /** 退役：停止召回与广播。（"学到坏经验"必须能退回去，否则适应度只是装饰） */
    public void retire(String skillId, String reason) {
        if (retiredSkills.add(skillId)) {
            log.warn("技能退役：{}（原因：{}，使用 {} 次，适应度 {}）", skillId, reason,
                    stats(skillId).uses(), String.format("%.2f", stats(skillId).fitness()));
        }
    }

    public void unretire(String skillId) {
        if (retiredSkills.remove(skillId)) {
            log.info("技能恢复：{}", skillId);
        }
    }

    public boolean isRetired(String skillId) {
        return retiredSkills.contains(skillId);
    }

    public Set<String> retiredSkillIds() {
        return Set.copyOf(retiredSkills);
    }

    /** 自动淘汰候选：用够了、但适应度掉到阈值以下。 */
    public List<String> retireCandidates() {
        HiveProperties.Skill config = properties.getSkill();
        List<String> candidates = new ArrayList<>();
        for (String skillId : versions.keySet()) {
            if (isRetired(skillId)) {
                continue;
            }
            SkillStats stats = stats(skillId);
            if (stats.uses() >= config.getRetireMinUses() && stats.fitness() < config.getRetireFitnessThreshold()) {
                candidates.add(skillId);
            }
        }
        candidates.sort(Comparator.naturalOrder());
        return candidates;
    }

    public List<SkillArtifact> promotableArtifacts() {
        List<SkillArtifact> out = new ArrayList<>();
        for (SkillArtifact artifact : allActive()) {
            if (promotable(artifact.skillId())) {
                out.add(artifact);
            }
        }
        return out;
    }

    /**
     * 召回：相关度（0~1）× 适应度 排序后再取前 max 条。
     * 相关度为 0 的技能一律不注入——把不相关的"经验"塞进上下文，比没有经验更糟。
     */
    @Override
    public List<SkillHint> hintsFor(String taskInput, int max) {
        if (taskInput == null || taskInput.isBlank() || max <= 0) {
            return List.of();
        }
        Map<String, Double> scored = new LinkedHashMap<>();
        for (SkillArtifact artifact : allActive()) {
            if (isRetired(artifact.skillId())) {
                continue;
            }
            double relevance = relevance(taskInput, artifact.trigger() + " " + artifact.title());
            if (relevance <= 0.0) {
                continue;
            }
            double fitness = stats(artifact.skillId()).fitness();
            scored.put(artifact.ref(), relevance * 2.0 + fitness);
        }
        Map<String, SkillArtifact> byRef = new LinkedHashMap<>();
        for (SkillArtifact artifact : allActive()) {
            byRef.put(artifact.ref(), artifact);
        }
        return scored.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(max)
                .map(entry -> {
                    SkillArtifact artifact = byRef.get(entry.getKey());
                    return new SkillHint(artifact.skillId(), artifact.version(), artifact.title(),
                            artifact.procedure(), stats(artifact.skillId()).fitness());
                })
                .toList();
    }

    public int size() {
        return versions.size();
    }

    /** 供测试清理上下文（Spring 上下文里有状态，测试之间需要隔离）。 */
    public synchronized void clear() {
        versions.clear();
        activeVersions.clear();
        statsBySkill.clear();
        retiredSkills.clear();
    }

    /**
     * 相关度 = max(词级重合, 字符二元组重合)。
     * 为什么要字符级：中文没有空格分词，词级匹配对"订单超时怎么办" vs "订单超时处理" 完全失效，
     * 而二元组重合能给出 0.5 这样的合理相关度。
     */
    static double relevance(String query, String text) {
        String q = query.toLowerCase().trim();
        String t = text.toLowerCase().trim();
        if (q.isEmpty() || t.isEmpty()) {
            return 0.0;
        }
        if (q.contains(t) || t.contains(q)) {
            return 1.0;
        }
        return Math.max(tokenOverlap(q, t), bigramOverlap(q, t));
    }

    private static double tokenOverlap(String q, String t) {
        List<String> queryTokens = tokens(q);
        List<String> textTokens = tokens(t);
        if (queryTokens.isEmpty() || textTokens.isEmpty()) {
            return 0.0;
        }
        long hit = queryTokens.stream().filter(textTokens::contains).count();
        return (double) hit / queryTokens.size();
    }

    private static double bigramOverlap(String q, String t) {
        Set<String> queryBigrams = bigrams(q);
        Set<String> textBigrams = bigrams(t);
        if (queryBigrams.isEmpty() || textBigrams.isEmpty()) {
            return 0.0;
        }
        long hit = queryBigrams.stream().filter(textBigrams::contains).count();
        return (double) hit / queryBigrams.size();
    }

    private static Set<String> bigrams(String text) {
        String cleaned = text.replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", "");
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 1 < cleaned.length(); i++) {
            out.add(cleaned.substring(i, i + 2));
        }
        return out;
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String token : text.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }

    public SkillArtifact require(String skillId) {
        return active(skillId).orElseThrow(() ->
                new BizException(ErrorCode.NOT_FOUND, "技能不存在：" + skillId));
    }
}
