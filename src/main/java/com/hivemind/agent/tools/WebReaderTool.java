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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页读取（"浏览器读"能力的轻量实现）：抓取页面 → 提取标题/正文/链接 → 交给模型。
 *
 * 诚实说明能力边界：这里**不执行 JavaScript、不做真实浏览器渲染**。
 * 需要 JS 渲染或登录态的页面必须上真实浏览器（Playwright 类方案），属于后续项；
 * 把这点写清楚，比假装"我们有浏览器"更靠谱。
 *
 * 安全：主机白名单（默认只允许本机）+ 响应体积上限 + 不跟随重定向（避免借跳转绕过白名单）。
 */
public final class WebReaderTool implements Tool {

    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern LINK = Pattern.compile("(?is)<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[\\t\\x0B\\f\\r ]+");

    private final HiveProperties properties;
    private final HttpClient http;
    private final List<String> allowHosts;

    public WebReaderTool(HiveProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getAgent().getHttpTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.allowHosts = HiveProperties.splitCsv(properties.getAgent().getWebAllowHosts());
    }

    public record Extracted(String title, String text, List<String> links) {
    }

    @Override
    public String name() {
        return "web_read";
    }

    @Override
    public String description() {
        return "读取网页并提取标题、正文与链接（不执行 JavaScript；仅允许白名单主机）";
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
        if (uri.getHost() == null || allowHosts.stream().noneMatch(host -> host.equalsIgnoreCase(uri.getHost()))) {
            return ToolResult.fail("主机不在白名单内：" + uri.getHost() + "（当前白名单=" + allowHosts + "）");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getAgent().getHttpTimeoutMillis()))
                    .header("User-Agent", "HiveMind/1.1 (+web_read)")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                return ToolResult.fail("HTTP " + response.statusCode() + "：" + uri);
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            int limit = Math.max(1000, properties.getAgent().getWebMaxBytes());
            String html = new String(body, 0, Math.min(body.length, limit), java.nio.charset.StandardCharsets.UTF_8);
            Extracted extracted = extract(html, 4000);
            String summary = "标题：" + extracted.title()
                    + "\n正文摘要：" + extracted.text()
                    + "\n链接（最多 10 条）：" + extracted.links().stream().limit(10).toList();
            return ToolResult.ok(summary);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("网页读取被中断");
        } catch (Exception e) {
            return ToolResult.fail("网页读取失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** HTML → 标题/正文/链接。抽成静态方法便于单测（这是"读"能力里最容易被写错的部分）。 */
    public static Extracted extract(String html, int maxChars) {
        if (html == null || html.isBlank()) {
            return new Extracted("", "", List.of());
        }
        String withoutScripts = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        Matcher titleMatcher = TITLE.matcher(withoutScripts);
        String title = titleMatcher.find() ? clean(titleMatcher.group(1)) : "";

        List<String> links = new ArrayList<>();
        Matcher linkMatcher = LINK.matcher(withoutScripts);
        while (linkMatcher.find() && links.size() < 50) {
            String href = linkMatcher.group(1).trim();
            String label = clean(linkMatcher.group(2));
            if (!href.isEmpty() && !href.startsWith("#") && !href.toLowerCase().startsWith("javascript:")) {
                links.add(label.isBlank() ? href : label + " → " + href);
            }
        }

        List<String> blocks = new ArrayList<>();
        String textOnly = TAG.matcher(withoutScripts).replaceAll("\n");
        for (String line : textOnly.split("\n")) {
            String cleaned = clean(line);
            if (!cleaned.isBlank()) {
                blocks.add(cleaned);
            }
        }
        String text = WHITESPACE.matcher(String.join(" ", blocks)).replaceAll(" ").trim();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "...(已截断)";
        }
        return new Extracted(title, text, List.copyOf(links));
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String value = TAG.matcher(raw).replaceAll(" ");
        value = value.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return WHITESPACE.matcher(value).replaceAll(" ").trim();
    }
}
