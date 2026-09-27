package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具执行结果（统一返回值）。
 */
public record ToolResult(
    boolean success,
    Object data,
    String error,
    long durationMs
) {
    public static ToolResult ok(Object data, long durationMs) {
        return new ToolResult(true, data, null, durationMs);
    }

    public static ToolResult fail(String error, long durationMs) {
        return new ToolResult(false, null, error, durationMs);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new ConcurrentHashMap<>();
        m.put("success", success);
        if (data != null) m.put("data", data);
        if (error != null) m.put("error", error);
        m.put("duration_ms", durationMs);
        return m;
    }
}