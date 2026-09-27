package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.data.json;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * JSON 处理工具（支持格式化、提取字段、解析、合并）。
 *
 * 参数（按 action 选择）：
 * <ul>
 *   <li>action: 操作类型（parse / stringify / extract / merge），必填</li>
 *   <li>json: JSON 字符串</li>
 *   <li>path: 提取路径（仅 action=extract，例如 $.user.name）</li>
 *   <li>target / source: 合并的两个 JSON</li>
 * </ul>
 */
@Tool(name = "json.process", description = "JSON 解析/格式化/字段提取", category = "data.json")
public class JsonProcess extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("action")) {
            throw new IllegalArgumentException("Missing required arg: action");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ToolResult doExecute(Map<String, Object> args) throws Exception {
        String action = (String) args.get("action");
        switch (action) {
            case "parse" -> {
                String json = (String) args.get("json");
                Object parsed = MAPPER.readValue(json, Object.class);
                return ToolResult.ok(Map.of("parsed", parsed), 0);
            }
            case "stringify" -> {
                Object obj = args.get("data");
                String pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
                return ToolResult.ok(Map.of("json", pretty), 0);
            }
            case "extract" -> {
                String json = (String) args.get("json");
                String path = (String) args.get("path");
                Object root = MAPPER.readValue(json, Object.class);
                Object extracted = extractPath(root, path);
                return ToolResult.ok(Map.of("value", extracted), 0);
            }
            case "merge" -> {
                String targetJson = (String) args.get("target");
                String sourceJson = (String) args.get("source");
                Map<String, Object> target = MAPPER.readValue(targetJson, Map.class);
                Map<String, Object> source = MAPPER.readValue(sourceJson, Map.class);
                target.putAll(source);
                String merged = MAPPER.writeValueAsString(target);
                return ToolResult.ok(Map.of("merged", merged), 0);
            }
            default -> {
                return ToolResult.fail("Unknown action: " + action, 0);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object extractPath(Object root, String path) {
        if (path == null || path.isBlank()) return root;
        String p = path.startsWith("$") ? path.substring(1) : path;
        if (p.startsWith(".")) p = p.substring(1);
        Object current = root;
        for (String seg : p.split("\\.")) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(seg);
            } else {
                return null;
            }
        }
        return current;
    }
}