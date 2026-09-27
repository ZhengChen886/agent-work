package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.agent.tool;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolRegistry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Lazy;

/**
 * 列出所有可用工具。
 */
@Tool(name = "agent.tool.list", description = "列出所有可用的 Tools", category = "agent.tool")
public class ListTools extends BaseTool {

    private final ToolRegistry registry;

    public ListTools(@Lazy ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) {
        var infos = registry.getToolInfos();
        java.util.Map<String, java.util.List<Map<String, Object>>> byCategory = new HashMap<>();
        for (var info : infos) {
            byCategory.computeIfAbsent(info.category(), k -> new java.util.ArrayList<>())
                    .add(Map.of("name", info.name(), "description", info.description()));
        }
        return ToolResult.ok(Map.of(
                "count", infos.size(),
                "by_category", byCategory
        ), 0);
    }
}