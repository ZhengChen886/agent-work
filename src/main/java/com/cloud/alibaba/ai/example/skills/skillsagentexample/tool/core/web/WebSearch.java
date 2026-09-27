package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.core.web;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 互联网搜索工具（单次调用即可返回结构化结果）。
 *
 * <p>默认走 Bing HTML 搜索页；若 Bing 解析不到结果（反爬 / 页面结构变化），
 * 自动切换到 DuckDuckGo HTML 版兜底，避免"搜索工具暂时出现技术问题"这类不可诊断的失败。
 * 不依赖外部 API Key。</p>
 *
 * 参数：
 * <ul>
 *   <li>query: 搜索关键词（必填）</li>
 *   <li>count: 返回结果条数（可选，默认 5，范围 1-10）</li>
 * </ul>
 */
@Tool(name = "web.search",
        description = "搜索互联网并一次性返回结构化结果（标题、链接、摘要）。参数：query(必填,搜索关键词)、count(可选,1-10,默认5)。一次调用即可完成搜索，不要在循环中重复调用同一关键词。",
        category = "core.web")
public class WebSearch extends BaseTool {

    private static final Pattern ITEM_PATTERN = Pattern.compile("<li class=\"b_algo\".*?</li>", Pattern.DOTALL);
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern SNIPPET_PATTERN = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    // DuckDuckGo HTML 版（/html/ 端点）结果：标题链接 + 摘要
    private static final Pattern DDG_TITLE_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern DDG_SNIPPET_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    // DDG 重定向链接：/l/?uddg=<urlencoded真实链接>
    private static final Pattern DDG_REDIR_PATTERN = Pattern.compile("uddg=([^&]+)");

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int BING_MAX_ATTEMPTS = 2;

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("query")) {
            throw new IllegalArgumentException("Missing required arg: query");
        }
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) throws Exception {
        String query = (String) args.get("query");
        int count = args.containsKey("count")
                ? Math.max(1, Math.min(10, ((Number) args.get("count")).intValue()))
                : 5;

        // 引擎 1：Bing（带 1 次自动重试，吸收偶发的空结果 / 反爬页）
        ToolResult bing = tryBing(query, count);
        if (bing != null) {
            return bing;
        }

        // 引擎 2：DuckDuckGo 兜底
        ToolResult ddg = tryDuckDuckGo(query, count);
        if (ddg != null) {
            return ddg;
        }

        return ToolResult.fail(
                "搜索失败（Bing 与 DuckDuckGo 均无结果）。请基于已有知识作答，不要反复重试。", 0);
    }

    private ToolResult tryBing(String query, int count) {
        String lastNote = "无";
        for (int attempt = 0; attempt < BING_MAX_ATTEMPTS; attempt++) {
            try {
                String url = "https://www.bing.com/search?q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)
                        + "&count=" + count + "&setlang=zh-CN";
                HttpResponse<String> response = get(url);
                if (response.statusCode() >= 400) {
                    lastNote = "HTTP " + response.statusCode();
                    continue;
                }
                List<SearchResult> results = parseBing(response.body(), count);
                if (results.isEmpty()) {
                    lastNote = "解析 0 条（可能返回反爬/空页面）";
                    sleepQuietly(1000L);
                    continue;
                }
                return ToolResult.ok(format(results, "Bing"), 0);
            } catch (Exception e) {
                lastNote = e.getMessage();
                sleepQuietly(1000L);
            }
        }
        log.warn("[web.search] Bing 引擎失败（{}），切换 DuckDuckGo", lastNote);
        return null;
    }

    private ToolResult tryDuckDuckGo(String query, int count) {
        try {
            String url = "https://duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> response = get(url);
            if (response.statusCode() >= 400) {
                log.warn("[web.search] DuckDuckGo HTTP {}", response.statusCode());
                return null;
            }
            List<SearchResult> results = parseDuckDuckGo(response.body(), count);
            if (results.isEmpty()) {
                log.warn("[web.search] DuckDuckGo 解析 0 条结果");
                return null;
            }
            return ToolResult.ok(format(results, "DuckDuckGo"), 0);
        } catch (Exception e) {
            log.warn("[web.search] DuckDuckGo 兜底失败: {}", e.getMessage());
            return null;
        }
    }

    /** Bing：li.b_algo 结果块 → h2>a 标题链接 + p 摘要 */
    private List<SearchResult> parseBing(String html, int max) {
        List<SearchResult> results = new ArrayList<>();
        Matcher itemMatcher = ITEM_PATTERN.matcher(html);
        while (itemMatcher.find() && results.size() < max) {
            String item = itemMatcher.group();
            Matcher linkMatcher = LINK_PATTERN.matcher(item);
            if (!linkMatcher.find()) continue;
            String url = htmlUnescape(linkMatcher.group(1));
            String title = stripTags(htmlUnescape(linkMatcher.group(2)));
            String snippet = "";
            Matcher snippetMatcher = SNIPPET_PATTERN.matcher(item);
            if (snippetMatcher.find()) {
                snippet = stripTags(htmlUnescape(snippetMatcher.group(1)));
                if (snippet.length() > 300) snippet = snippet.substring(0, 300) + "…";
            }
            results.add(new SearchResult(title, url, snippet));
        }
        return results;
    }

    /** DuckDuckGo HTML 版：result__a 标题 + result__snippet 摘要，重定向链接解析真实 URL */
    private List<SearchResult> parseDuckDuckGo(String html, int max) {
        List<SearchResult> results = new ArrayList<>();
        Matcher titleMatcher = DDG_TITLE_PATTERN.matcher(html);
        List<String> snippets = new ArrayList<>();
        for (Matcher m = DDG_SNIPPET_PATTERN.matcher(html); m.find(); ) {
            snippets.add(stripTags(htmlUnescape(m.group(1))));
        }
        int idx = 0;
        while (titleMatcher.find() && results.size() < max) {
            String url = resolveDdgUrl(titleMatcher.group(1));
            String title = stripTags(htmlUnescape(titleMatcher.group(2)));
            String snippet = idx < snippets.size() ? snippets.get(idx) : "";
            if (snippet.length() > 300) snippet = snippet.substring(0, 300) + "…";
            results.add(new SearchResult(title, url, snippet));
            idx++;
        }
        return results;
    }

    /** 把 DDG /l/?uddg=xxx 重定向链接还原为真实 URL；普通链接原样返回 */
    private String resolveDdgUrl(String raw) {
        Matcher m = Pattern.compile("uddg=([^&]+)").matcher(raw);
        if (m.find()) {
            try {
                return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return raw;
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String format(List<SearchResult> results, String engine) {
        StringBuilder sb = new StringBuilder();
        sb.append("（来源：").append(engine).append("）\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title()).append('\n');
            sb.append("   链接: ").append(r.url()).append('\n');
            if (!r.snippet().isEmpty()) {
                sb.append("   摘要: ").append(r.snippet()).append('\n');
            }
        }
        return sb.toString();
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stripTags(String s) {
        return TAG_PATTERN.matcher(s).replaceAll("").trim();
    }

    private static String htmlUnescape(String s) {
        return s == null ? null
                : s.replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace("&#x27;", "'")
                        .replace("&ensp;", " ")
                        .replace("&nbsp;", " ");
    }

    private record SearchResult(String title, String url, String snippet) {
    }
}
