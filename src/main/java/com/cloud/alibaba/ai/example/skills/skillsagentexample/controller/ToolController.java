package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolRegistry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolRegistry.ToolInfo;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具管理 HTTP 接口（统一入口）。
 *
 * <ul>
 *   <li>GET  /api/tools            — 列出所有工具</li>
 *   <li>GET  /api/tools/categories — 按分类分组</li>
 *   <li>GET  /api/tools/{name}     — 工具详情</li>
 *   <li>POST /api/tools/{name}/invoke — 调用工具</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    @Autowired
    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<ToolInfo> tools = toolRegistry.getToolInfos();
        Map<String, Object> result = new HashMap<>();
        result.put("count", tools.size());
        result.put("tools", tools);
        return result;
    }

    @GetMapping("/categories")
    public Map<String, List<String>> byCategory() {
        return toolRegistry.getToolsByCategory();
    }

    @GetMapping("/{name}")
    public Map<String, Object> info(@PathVariable String name) {
        return toolRegistry.getTool(name)
                .map(t -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", t.getName());
                    info.put("description", t.getDescription());
                    info.put("category", t.getCategory());
                    info.put("enabled", t.isEnabled());
                    info.put("class", t.getClass().getSimpleName());
                    return info;
                })
                .orElse(Map.of("error", "Tool not found: " + name));
    }

    @PostMapping("/{name}/invoke")
    public ToolResult invoke(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> args = body != null ? body : Map.of();
        return toolRegistry.invoke(name, null, args);
    }
}