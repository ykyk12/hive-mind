package com.hivemind.agent.tools;

import com.hivemind.agent.RiskLevel;
import com.hivemind.agent.Tool;
import com.hivemind.agent.ToolContext;
import com.hivemind.agent.ToolResult;
import com.hivemind.config.HiveProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 HTTP GET（用于查资料、调用外部 API）。
 *
 * 两道限制：主机白名单（默认只允许本机）+ MEDIUM 风险（需审批令牌）。
 * "能访问任意 URL 的 Agent" 一旦被提示注入利用就是内网探测器，所以默认不开全网。
 */
public final class HttpJsonTool implements Tool {

    private final HiveProperties properties;
    private final HttpClient http;
    private final List<String> allowHosts;

    public HttpJsonTool(HiveProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getAgent().getHttpTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.allowHosts = HiveProperties.splitCsv(properties.getAgent().getHttpAllowHosts());
    }

    @Override
    public String name() {
        return "http_get";
    }

    @Override
    public String description() {
        return "发起 HTTP GET 请求获取文本/JSON（仅允许白名单主机）";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public Map<String, String> parameterSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("url", "完整 URL，主机必须在白名单内");
        return schema;
    }

    @Override
    public ToolResult invoke(ToolContext context, Map<String, Object> args) {
        Object raw = args.get("url");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return ToolResult.fail("url 必填");
        }
        URI uri;
        try {
            uri = URI.create(String.valueOf(raw));
        } catch (RuntimeException e) {
            return ToolResult.fail("URL 不合法：" + raw);
        }
        if (uri.getHost() == null || allowHosts.stream().noneMatch(h -> h.equalsIgnoreCase(uri.getHost()))) {
            return ToolResult.fail("主机不在白名单内：" + uri.getHost() + "（当前白名单=" + allowHosts + "）");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getAgent().getHttpTimeoutMillis()))
                    .header("Accept", "application/json, text/plain;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            return ToolResult.ok("HTTP " + response.statusCode() + " " + body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("请求被中断");
        } catch (Exception e) {
            return ToolResult.fail("请求失败：" + e.getMessage());
        }
    }
}
