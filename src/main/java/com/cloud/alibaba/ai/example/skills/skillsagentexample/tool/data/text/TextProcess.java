package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.data.text;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本处理工具（提取、替换、计数、转换）。
 *
 * 参数（按 action 选择）：
 * <ul>
 *   <li>action: 操作类型（extract / replace / count / case），必填</li>
 *   <li>text: 文本内容</li>
 *   <li>pattern: 正则表达式（extract / replace）</li>
 *   <li>replacement: 替换内容（replace）</li>
 *   <li>mode: 转换模式（case: upper / lower / title）</li>
 * </ul>
 */
@Tool(name = "text.process", description = "文本提取/替换/转换", category = "data.text")
public class TextProcess extends BaseTool {

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("action")) {
            throw new IllegalArgumentException("Missing required arg: action");
        }
        if (!args.containsKey("text")) {
            throw new IllegalArgumentException("Missing required arg: text");
        }
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) throws Exception {
        String action = (String) args.get("action");
        String text = (String) args.get("text");

        switch (action) {
            case "extract" -> {
                String patternStr = (String) args.get("pattern");
                Pattern pattern = Pattern.compile(patternStr);
                Matcher matcher = pattern.matcher(text);
                java.util.List<String> matches = new java.util.ArrayList<>();
                while (matcher.find()) {
                    matches.add(matcher.group());
                }
                return ToolResult.ok(Map.of("matches", matches, "count", matches.size()), 0);
            }
            case "replace" -> {
                String patternStr = (String) args.get("pattern");
                String replacement = (String) args.get("replacement");
                String result = text.replaceAll(patternStr, replacement);
                return ToolResult.ok(Map.of("text", result), 0);
            }
            case "count" -> {
                String patternStr = (String) args.get("pattern");
                if (patternStr == null || patternStr.isBlank()) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("chars", text.length());
                    data.put("lines", text.split("\n", -1).length);
                    data.put("words", text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length);
                    return ToolResult.ok(data, 0);
                }
                int count = (text.length() - text.replaceAll(patternStr, "").length())
                        / Math.max(1, patternStr.length());
                return ToolResult.ok(Map.of("count", count), 0);
            }
            case "case" -> {
                String mode = (String) args.get("mode");
                String result = switch (mode) {
                    case "upper" -> text.toUpperCase();
                    case "lower" -> text.toLowerCase();
                    case "title" -> toTitleCase(text);
                    default -> text;
                };
                return ToolResult.ok(Map.of("text", result), 0);
            }
            default -> {
                return ToolResult.fail("Unknown action: " + action, 0);
            }
        }
    }

    private String toTitleCase(String input) {
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c)) {
                capitalizeNext = true;
                sb.append(c);
            } else if (capitalizeNext) {
                sb.append(Character.toTitleCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}