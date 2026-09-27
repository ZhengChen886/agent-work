package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.core.http;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 请求工具。
 *
 * 参数：
 * <ul>
 *   <li>url: 请求 URL（必填）</li>
 *   <li>method: HTTP 方法，默认 GET</li>
 *   <li>headers: 请求头（可选，Map）</li>
 *   <li>body: 请求体（可选）</li>
 *   <li>timeout_seconds: 超时秒数，默认 30</li>
 * </ul>
 *
 * 适用范围：仅用于调用用户指定/项目内部/可信 API 接口（REST、GraphQL、OpenAPI）。
 * 禁止使用本工具抓取公开网站或搜索引擎（weather.com.cn、baidu.com、bing.com、
 * google.com、so.com、sogou.com 等）的网页正文；获取这类内容请改用 web.search。
 */
@Tool(name = "http.request", description = "发送 HTTP 请求（仅限调用可信 API 接口；禁止用于拉取公开网站/搜索引擎网页正文，此类场景请改用 web.search）", category = "core.http")
public class HttpCall extends BaseTool {

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("url")) {
            throw new IllegalArgumentException("Missing required arg: url");
        }
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) throws Exception {
        String url = (String) args.get("url");
        String method = args.containsKey("method")
                ? ((String) args.get("method")).toUpperCase() : "GET";
        int timeoutSeconds = args.containsKey("timeout_seconds")
                ? ((Number) args.get("timeout_seconds")).intValue() : 30;
        Object body = args.get("body");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        if (args.containsKey("headers")) {
            Object headersObj = args.get("headers");
            if (headersObj instanceof Map) {
                ((Map<?, ?>) headersObj).forEach((k, v) ->
                        reqBuilder.header(String.valueOf(k), String.valueOf(v)));
            }
        }

        java.net.http.HttpRequest.BodyPublisher publisher = body == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(body.toString());

        java.net.http.HttpRequest request;
        switch (method) {
            case "POST" -> request = reqBuilder.POST(publisher).build();
            case "PUT" -> request = reqBuilder.PUT(publisher).build();
            case "DELETE" -> request = reqBuilder.DELETE().build();
            default -> request = reqBuilder.GET().build();
        }

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> data = new HashMap<>();
        data.put("status_code", response.statusCode());
        data.put("body", response.body());
        data.put("headers", response.headers().map());

        if (response.statusCode() >= 400) {
            return ToolResult.fail("HTTP " + response.statusCode() + ": " + response.body(), 0);
        }
        return ToolResult.ok(data, 0);
    }
}