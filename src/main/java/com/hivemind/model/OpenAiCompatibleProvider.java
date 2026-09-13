package com.hivemind.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemind.config.HiveProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的提供方（DeepSeek / 通义 / 智谱 / 自建 vLLM 等都走这个协议）。
 * 只依赖 JDK HttpClient，不引第三方 SDK——少一个依赖就少一类版本冲突。
 */
public final class OpenAiCompatibleProvider implements ModelProvider {

    private final ModelCaps caps;
    private final HiveProperties.ModelConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public OpenAiCompatibleProvider(ModelCaps caps, HiveProperties.ModelConfig config, ObjectMapper objectMapper) {
        this.caps = caps;
        this.config = config;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ModelCaps caps() {
        return caps;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        if (!available()) {
            throw new ModelUnavailableException(caps.id() + " 未配置 apiKey/baseUrl");
        }
        long start = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            List<Map<String, String>> messages = new ArrayList<>();
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", request.systemPrompt()));
            }
            for (ChatMessage message : request.messages()) {
                messages.add(Map.of("role", message.role(),
                        "content", message.content() == null ? "" : message.content()));
            }
            body.put("messages", messages);
            body.put("max_tokens", request.maxTokens());
            body.put("temperature", request.temperature());
            body.put("stream", false);
            if (request.jsonMode()) {
                body.put("response_format", Map.of("type", "json_object"));
            }

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - start) / 1_000_000L;
            if (response.statusCode() >= 400) {
                throw new ModelUnavailableException(caps.id() + " 返回 HTTP " + response.statusCode()
                        + "：" + truncate(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                throw new ModelUnavailableException(caps.id() + " 返回内容为空");
            }
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(estimateTokens(request));
            int completionTokens = root.path("usage").path("completion_tokens").asInt(Math.max(1, text.length() / 4));
            return new CompletionResponse(caps.id(), config.getModel(), text, promptTokens, completionTokens, latency);
        } catch (ModelUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException(caps.id() + " 调用被中断", e);
        } catch (IOException e) {
            throw new ModelUnavailableException(caps.id() + " 网络异常：" + e.getClass().getSimpleName(), e);
        } catch (RuntimeException e) {
            throw new ModelUnavailableException(caps.id() + " 调用失败：" + e.getMessage(), e);
        }
    }

    /** baseUrl 可能已带 /v1，避免拼成 /v1/v1/chat/completions。 */
    private String endpoint() {
        String base = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim().replaceAll("/+$", "");
        return base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    }

    private int estimateTokens(CompletionRequest request) {
        int chars = request.messages().stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        return Math.max(1, chars / 4);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
