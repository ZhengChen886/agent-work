package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.agent.tool;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolRegistry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Lazy;

/**
 * 调用其他工具（允许 Agent 链式调用工具）。
 *
 * 参数：
 * <ul>
 *   <li>tool_name: 工具名称（必填）</li>
 *   <li>arguments: 参数 Map（必填）</li>
 * </ul>
 */
@Tool(name = "agent.tool.invoke", description = "调用指定的工具", category = "agent.tool")
public class InvokeTool extends BaseTool {

    private final ToolRegistry registry;

    public InvokeTool(@Lazy ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("tool_name")) {
            throw new IllegalArgumentException("Missing required arg: tool_name");
        }
        if (!args.containsKey("arguments")) {
            throw new IllegalArgumentException("Missing required arg: arguments");
        }
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) {
        String toolName = (String) args.get("tool_name");
        Object argumentsObj = args.get("arguments");

        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = argumentsObj instanceof Map
                ? (Map<String, Object>) argumentsObj
                : new HashMap<>();

        ToolResult result = registry.invoke(toolName, null, arguments);
        return result;
    }
}