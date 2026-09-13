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
 * 网页提交（"浏览器写"能力）：向白名单主机 POST 一个 JSON 表单。
 *
 * 定为 HIGH 风险的原因：写操作会改变外部状态（下单、提交、发帖），失败往往不可撤销，
 * 所以必须带审批令牌 + 主机白名单 + 体积上限 + 不跟随重定向。
 * 不做 JS 渲染、不保存登录态——需要登录态的写操作应该由人来做，而不是交给自动流程。
 */
public final class WebSubmitTool implements Tool {

    private final HiveProperties properties;
    private final HttpClient http;
    private final List<String> allowHosts;

    public WebSubmitTool(HiveProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getAgent().getHttpTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.allowHosts = HiveProperties.splitCsv(properties.getAgent().getWebAllowHosts());
    }

    @Override
    public String name() {
        return "web_submit";
    }

    @Override
    public String description() {
        return "向白名单主机的 URL 提交 JSON 表单（写操作，需审批令牌）";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.HIGH;
    }

    @Override
    public Map<String, String> parameterSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("url", "提交地址，主机必须在白名单内");
        schema.put("body", "JSON 字符串形式的请求体");
        return schema;
    }

    @Override
    public ToolResult invoke(ToolContext context, Map<String, Object> args) {
        Object rawUrl = args.get("url");
        if (rawUrl == null || String.valueOf(rawUrl).isBlank()) {
            return ToolResult.fail("url 必填");
        }
        URI uri;
        try {
            uri = URI.create(String.valueOf(rawUrl));
        } catch (RuntimeException e) {
            return ToolResult.fail("URL 不合法：" + rawUrl);
        }
        if (uri.getHost() == null || allowHosts.stream().noneMatch(host -> host.equalsIgnoreCase(uri.getHost()))) {
            return ToolResult.fail("主机不在白名单内：" + uri.getHost() + "（当前白名单=" + allowHosts + "）");
        }
        String body = String.valueOf(args.getOrDefault("body", "{}"));
        int limit = Math.max(1000, properties.getAgent().getWebMaxBytes());
        if (body.length() > limit) {
            return ToolResult.fail("请求体过大（" + body.length() + " > " + limit + " 字符）");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getAgent().getHttpTimeoutMillis()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "HiveMind/1.1 (+web_submit)")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body() == null ? "" : response.body();
            if (responseBody.length() > limit) {
                responseBody = responseBody.substring(0, limit) + "...(已截断)";
            }
            if (response.statusCode() >= 400) {
                return ToolResult.fail("HTTP " + response.statusCode() + "：" + responseBody);
            }
            return ToolResult.ok("HTTP " + response.statusCode() + " 提交成功，响应：" + responseBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("网页提交被中断");
        } catch (Exception e) {
            return ToolResult.fail("网页提交失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
