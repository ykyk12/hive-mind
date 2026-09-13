package com.hivemind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * hive.* 全部配置。
 *
 * 这里刻意不引数据库：节点状态在内存、制品落磁盘。
 * 边界（多节点一致性、持久化）写在 README 的「已知边界」，不假装已是生产形态。
 */
@Data
@ConfigurationProperties(prefix = "hive")
public class HiveProperties {

    private Node node = new Node();
    private Agent agent = new Agent();
    private Skill skill = new Skill();
    private Evolve evolve = new Evolve();
    private Governance governance = new Governance();
    private List<ModelConfig> models = new ArrayList<>();

    /** 节点身份与集群参数。 */
    @Data
    public static class Node {
        private String id = "node-1";
        private String endpoint = "http://127.0.0.1:8101";
        private int weight = 100;
        /** 逗号分隔的同伴地址；留空即单节点集群。 */
        private String peers = "";
        /** 本节点声明的能力标签，供大脑做任务匹配。 */
        private String capabilities = "chat,reasoning,code,search,summarize";
        /** 节点间通信的共享密钥；留空表示不校验（仅限本机演示，生产必须配置）。 */
        private String clusterToken = "";
        private long heartbeatMillis = 1000;
        private long leaseMillis = 4000;

        public List<String> peerList() {
            return splitCsv(peers);
        }

        public Set<String> capabilitySet() {
            return new LinkedHashSet<>(splitCsv(capabilities));
        }
    }

    /** Agent 循环参数。 */
    @Data
    public static class Agent {
        private int maxSteps = 6;
        private int maxInjectedSkills = 4;
        /** 智能家居桥接地址（Home Assistant / 自建 MQTT-over-HTTP 网关）；留空则工具不可用。 */
        private String smartHomeUrl = "";
        private String smartHomeToken = "";
        /** 通用 HTTP 工具的主机白名单，默认只允许本机——默认放通全网的"通用请求工具"等于开后门。 */
        private String httpAllowHosts = "127.0.0.1,localhost";
        private long httpTimeoutMillis = 5000;
    }

    /** 经验/技能参数。 */
    @Data
    public static class Skill {
        /** 任务成功后是否自动蒸馏新技能。 */
        private boolean distillEnabled = true;
        /** 至少要几个不同节点验证过，才允许把技能推广到全网（防止单节点坏经验传染）。 */
        private int promotionMinNodes = 2;
        private double promotionMinFitness = 0.6;
        private int promotionMinUses = 3;
    }

    /** 自改（制品进化）参数。 */
    @Data
    public static class Evolve {
        private String root = "hive";
        /** 允许自动门禁放行的变更类型。 */
        private String autoApplyKinds = "SKILL,CONFIG,PLUGIN";
        private int maxPluginSourceLines = 400;
        private boolean requireSmokeCheck = true;

        public Set<String> autoApplyKindSet() {
            return new LinkedHashSet<>(splitCsv(autoApplyKinds));
        }
    }

    /** 治理（三权分立 + 门锁）参数。 */
    @Data
    public static class Governance {
        private boolean forbidSelfReview = true;
        private boolean kernelRequiresHuman = true;
        private boolean humanSignatureRequired = false;
        private int maxRiskScore = 60;
        private String adminKey = "hive-admin-key";
        private List<String> forbiddenPatterns = new ArrayList<>();
        private Direction direction = new Direction();
    }

    /**
     * 方向控制：门锁只放行"不违背目标向量"的变更。
     * 没有适应度/方向约束的自动进化就是随机漂移，所以这里把它做成硬约束。
     */
    @Data
    public static class Direction {
        /** 至少要说明预期收益，否则不予放行。 */
        private boolean requireBenefitStatement = true;
        /** 允许的相对成本上升幅度（超过则必须有成功率提升的证据）。 */
        private double maxCostIncreaseRatio = 0.30;
        /** 允许的声明风险分上限（评审风险分高于此值直接人工介入）。 */
        private int maxDeclaredRisk = 70;
    }

    /** 单个模型提供方配置。 */
    @Data
    public static class ModelConfig {
        private String id;
        /** mock | openai-compatible */
        private String provider = "openai-compatible";
        private String model;
        private String displayName;
        private String baseUrl;
        private String apiKey;
        private int contextWindow = 8192;
        /** 逗号分隔的能力标签：CHAT,REASONING,CODE,... */
        private String strengths = "CHAT";
        private double costPer1kIn = 0.0;
        private double costPer1kOut = 0.0;
        private long p50LatencyMs = 1000;
        private int weight = 10;

        public Set<com.hivemind.model.TaskType> strengthSet() {
            Set<com.hivemind.model.TaskType> set = new LinkedHashSet<>();
            for (String raw : splitCsv(strengths)) {
                try {
                    set.add(com.hivemind.model.TaskType.valueOf(raw.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // 未知能力标签忽略：配置写错不该让节点起不来
                }
            }
            return set;
        }
    }

    static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : Arrays.asList(raw.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
