package com.hivemind.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 召回质量评估集：把"经验能不能被正确召回"变成可回归的指标，而不是靠感觉。
 *
 * 评估在一个**独立的、固定夹具的技能库**上运行（不读运行时技能库）：
 * 否则评估结果会随运行状态漂移，指标就失去意义。
 * 夹具与用例都在 resources/eval/skill-recall-cases.json，改召回算法后跑一次即可比较。
 */
@Slf4j
@Service
public class SkillRecallEvaluation {

    private static final String CASES_PATH = "eval/skill-recall-cases.json";

    private final ObjectMapper objectMapper;
    private final HiveProperties properties;

    public SkillRecallEvaluation(ObjectMapper objectMapper, HiveProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public record CaseResult(String query, List<String> expected, List<String> recalled, boolean hit) {
    }

    public record Report(int caseCount, int hits, int topK, double precisionAtK, double recallAtK,
                         List<CaseResult> details, List<String> missed) {
    }

    /** 夹具文件结构：{"skills":[{skillId,title,trigger,procedure,tags}], "cases":[{query,expectAnyOf:[]}]} */
    record Fixture(List<FixtureSkill> skills, List<FixtureCase> cases) {
    }

    record FixtureSkill(String skillId, String title, String trigger, String procedure, List<String> tags) {
    }

    record FixtureCase(String query, List<String> expectAnyOf) {
    }

    public Report evaluate() {
        return evaluate(properties.getSkill().getRecallEvalTopK());
    }

    public Report evaluate(int topK) {
        Fixture fixture = loadFixture();
        SkillStore isolated = new SkillStore(properties);
        for (FixtureSkill skill : fixture.skills()) {
            isolated.publish(new SkillArtifact(skill.skillId(), 0, skill.title(), skill.trigger(),
                    skill.procedure(), skill.tags() == null ? Set.of() : Set.copyOf(skill.tags()),
                    Set.of("eval-fixture"), "eval-fixture", System.currentTimeMillis()));
        }

        List<CaseResult> details = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        int hits = 0;
        for (FixtureCase evalCase : fixture.cases()) {
            List<String> recalled = isolated.hintsFor(evalCase.query(), topK).stream()
                    .map(hint -> hint.skillId())
                    .toList();
            boolean hit = recalled.stream().anyMatch(id -> evalCase.expectAnyOf().contains(id));
            if (hit) {
                hits++;
            } else {
                missed.add(evalCase.query());
            }
            details.add(new CaseResult(evalCase.query(), evalCase.expectAnyOf(), recalled, hit));
        }

        int caseCount = fixture.cases().size();
        double precision = caseCount == 0 ? 0.0 : (double) hits / caseCount;
        int expectedTotal = fixture.cases().stream().mapToInt(c -> c.expectAnyOf().size()).sum();
        double recall = expectedTotal == 0 ? 0.0 : (double) hits / expectedTotal;
        log.info("召回评估：{}/{} 命中（precision@{}={}）", hits, caseCount, topK,
                String.format("%.2f", precision));
        return new Report(caseCount, hits, topK, precision, recall, List.copyOf(details), List.copyOf(missed));
    }

    public boolean passes() {
        return evaluate().precisionAtK() >= properties.getSkill().getRecallEvalMinPrecision();
    }

    /** 评估夹具（供测试直接断言用例集本身有效）。 */
    public Fixture fixture() {
        return loadFixture();
    }

    private Fixture loadFixture() {
        try (InputStream in = new ClassPathResource(CASES_PATH).getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Fixture fixture = objectMapper.readValue(json, new TypeReference<Fixture>() {
            });
            if (fixture.skills() == null || fixture.cases() == null) {
                throw new IllegalStateException("评估集缺少 skills 或 cases");
            }
            return fixture;
        } catch (IOException e) {
            throw new IllegalStateException("读取召回评估集失败：" + CASES_PATH + "（" + e.getMessage() + "）", e);
        }
    }
}
