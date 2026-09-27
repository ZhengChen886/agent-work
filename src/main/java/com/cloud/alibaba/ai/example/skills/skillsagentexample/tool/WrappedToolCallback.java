package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 将自定义 BaseTool 适配为 Spring AI 的 ToolCallback。
 *
 * Spring AI 的 Agent 通过 ToolCallback 调度工具，所以这里做一层薄包装。
 */
public class WrappedToolCallback implements ToolCallback {

    private final BaseTool tool;
    private final ToolDefinition definition;

    public WrappedToolCallback(BaseTool tool) {
        this.tool = tool;
        this.definition = ToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .inputSchema(buildInputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String input) {
        // 解析 JSON 输入为 Map，再调用工具
        Map<String, Object> args = parseInput(input);
        ToolResult result = tool.invoke(null, args);
        return resultToString(result);
    }

    private Map<String, Object> parseInput(String input) {
        // 简化：如果是 JSON 就解析，否则整个当作 raw 参数
        Map<String, Object> result = new HashMap<>();
        if (input == null || input.isBlank()) {
            return result;
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.readValue(trimmed, Map.class);
            } catch (Exception e) {
                // 解析失败当作 raw
            }
        }
        result.put("raw", input);
        return result;
    }

    private String resultToString(ToolResult result) {
        if (!result.success()) {
            return "Error: " + (result.error() != null ? result.error() : "unknown");
        }
        Object data = result.data();
        if (data == null) return "success";
        return data.toString();
    }

    private String buildInputSchema() {
        // 简化的 schema，子类可重写提供更详细的定义
        return "{\"type\":\"object\",\"additionalProperties\":true}";
    }
}