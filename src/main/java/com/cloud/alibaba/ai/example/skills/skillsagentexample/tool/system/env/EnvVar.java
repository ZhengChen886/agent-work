package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.system.env;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.util.HashMap;
import java.util.Map;

/**
 * 环境变量工具。
 *
 * 参数（按 action 选择）：
 * <ul>
 *   <li>action: 操作类型（get / set / list），必填</li>
 *   <li>key: 环境变量名（get/set）</li>
 *   <li>value: 环境变量值（set）</li>
 * </ul>
 */
@Tool(name = "system.env", description = "读取/设置环境变量", category = "system.env")
public class EnvVar extends BaseTool {

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("action")) {
            throw new IllegalArgumentException("Missing required arg: action");
        }
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) {
        String action = (String) args.get("action");
        switch (action) {
            case "get" -> {
                String key = (String) args.get("key");
                String value = System.getenv(key);
                if (value == null) value = System.getProperty(key);
                Map<String, Object> data = new HashMap<>();
                data.put("key", key);
                data.put("value", value);
                data.put("found", value != null);
                return ToolResult.ok(data, 0);
            }
            case "set" -> {
                String key = (String) args.get("key");
                String value = (String) args.get("value");
                System.setProperty(key, value);
                return ToolResult.ok(Map.of("key", key, "value", value, "set", true), 0);
            }
            case "list" -> {
                Map<String, String> env = System.getenv();
                return ToolResult.ok(Map.of("count", env.size(), "env", env), 0);
            }
            default -> {
                return ToolResult.fail("Unknown action: " + action, 0);
            }
        }
    }
}