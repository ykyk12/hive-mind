package com.hivemind.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 从配置装配所有模型提供方；未知 provider 类型跳过并告警，而不是让节点起不来。 */
@Slf4j
@Component
public class ProviderRegistry {

    private final List<ModelProvider> providers = new ArrayList<>();

    public ProviderRegistry(HiveProperties properties, ObjectMapper objectMapper) {
        for (HiveProperties.ModelConfig config : properties.getModels()) {
            String kind = config.getProvider() == null ? "" : config.getProvider().trim().toLowerCase();
            ModelCaps caps = capsOf(config);
            switch (kind) {
                case "mock" -> providers.add(new MockModelProvider(caps, objectMapper));
                case "openai-compatible" -> providers.add(new OpenAiCompatibleProvider(caps, config, objectMapper));
                default -> log.warn("未知 provider 类型，已跳过：id={} provider={}", config.getId(), config.getProvider());
            }
        }
        log.info("模型提供方装配完成：{} 个（其中已配置密钥 {} 个）",
                providers.size(), providers.stream().filter(ModelProvider::available).count());
    }

    public List<ModelProvider> all() {
        return List.copyOf(providers);
    }

    public List<ModelProvider> available() {
        return providers.stream().filter(ModelProvider::available).toList();
    }

    public Optional<ModelProvider> byId(String id) {
        return providers.stream().filter(p -> p.caps().id().equals(id)).findFirst();
    }

    public static ModelCaps capsOf(HiveProperties.ModelConfig config) {
        boolean configured = "mock".equalsIgnoreCase(config.getProvider())
                || (config.getApiKey() != null && !config.getApiKey().isBlank()
                && config.getBaseUrl() != null && !config.getBaseUrl().isBlank());
        return new ModelCaps(
                config.getId(),
                config.getProvider(),
                config.getModel(),
                config.getDisplayName() == null ? config.getId() : config.getDisplayName(),
                config.getContextWindow(),
                Set.copyOf(config.strengthSet()),
                config.getCostPer1kIn(),
                config.getCostPer1kOut(),
                config.getP50LatencyMs(),
                config.getWeight(),
                configured);
    }

    /** 供 /api/v1/models 展示的诊断信息（不含任何密钥）。 */
    public List<Map<String, Object>> diagnostics() {
        return providers.stream().map(p -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>(p.diagnostics());
            map.put("model", p.caps().model());
            return map;
        }).toList();
    }
}
