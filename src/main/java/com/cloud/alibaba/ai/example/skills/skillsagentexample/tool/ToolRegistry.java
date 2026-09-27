package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 工具注册中心（统一管理所有工具）。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>扫描所有标注了 {@link Tool} 注解的类并自动注册</li>
 *   <li>提供统一的 {@link #invoke} 调用入口</li>
 *   <li>提供统一的列表查询、分类查询</li>
 *   <li>支持转换为 Spring AI 的 {@link ToolCallback}，供大模型调用</li>
 * </ul>
 *
 * <p>使用方式：</p>
 * <pre>
 *   // Java 调用
 *   ToolResult result = toolRegistry.invoke("file.read", "conv-id-1",
 *           Map.of("path", "/tmp/x.txt"));
 *
 *   // 给 Agent 用
 *   List&lt;ToolCallback&gt; callbacks = toolRegistry.getAllToolCallbacks();
 * </pre>
 */
@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ApplicationContext applicationContext;

    /** 工具实例：name → BaseTool */
    private final Map<String, BaseTool> tools = new ConcurrentHashMap<>();

    /** 分类映射：category → [name, ...] */
    private final Map<String, List<String>> toolsByCategory = new ConcurrentHashMap<>();

    @Autowired
    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Spring 启动后扫描所有 @Tool 标注的类。
     */
    @PostConstruct
    public void scanAndRegister() {
        log.info("Scanning for tools...");
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Tool.class);
        log.info("Found {} tools beans", beans.size());

        for (Object bean : beans.values()) {
            if (!(bean instanceof BaseTool tool)) {
                log.warn("Bean {} has @Tool but not extends BaseTool", bean.getClass().getName());
                continue;
            }
            register(tool);
        }

        log.info("Tool registry initialized with {} tools in {} categories",
                tools.size(), toolsByCategory.size());
        tools.forEach((name, tool) -> log.info("  - {} [{}] -> {}",
                name, tool.getCategory(), tool.getClass().getSimpleName()));
    }

    /**
     * 注册一个工具。
     */
    public void register(BaseTool tool) {
        if (!tool.isEnabled()) {
            log.info("Tool {} is disabled, skipping", tool.getName());
            return;
        }
        String name = tool.getName();
        if (tools.containsKey(name)) {
            log.warn("Duplicate tool name: {}, overwriting", name);
        }
        tools.put(name, tool);
        toolsByCategory.computeIfAbsent(tool.getCategory(), k -> new ArrayList<>()).add(name);
        log.info("Registered tool: {} ({})", name, tool.getClass().getSimpleName());
    }

    /**
     * 注销工具。
     */
    public void unregister(String name) {
        BaseTool tool = tools.remove(name);
        if (tool != null) {
            List<String> list = toolsByCategory.get(tool.getCategory());
            if (list != null) list.remove(name);
            log.info("Unregistered tool: {}", name);
        }
    }

    /**
     * 调用工具（统一入口）。
     */
    public ToolResult invoke(String name, String conversationId, Map<String, Object> args) {
        BaseTool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.fail("Tool not found: " + name, 0);
        }
        return tool.invoke(conversationId, args);
    }

    /**
     * 获取工具实例。
     */
    public Optional<BaseTool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 获取所有工具名称。
     */
    public Collection<String> getAllToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    /**
     * 获取所有工具实例。
     */
    public Collection<BaseTool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 按分类获取工具。
     */
    public List<BaseTool> getToolsByCategory(String category) {
        List<String> names = toolsByCategory.getOrDefault(category, List.of());
        return names.stream()
                .map(tools::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 获取所有分类。
     */
    public List<String> getCategories() {
        return new ArrayList<>(toolsByCategory.keySet());
    }

    /**
     * 获取所有分类及对应工具名（按分类排序，分类内按名称排序）。
     */
    public Map<String, List<String>> getToolsByCategory() {
        Map<String, List<String>> sorted = new LinkedHashMap<>();
        toolsByCategory.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(e -> {
                    List<String> names = e.getValue().stream().sorted().collect(Collectors.toList());
                    sorted.put(e.getKey(), names);
                });
        return sorted;
    }

    /**
     * 获取工具元信息列表（用于前端展示）。
     */
    public List<ToolInfo> getToolInfos() {
        return tools.values().stream()
                .map(t -> new ToolInfo(t.getName(), t.getDescription(), t.getCategory()))
                .sorted(Comparator.comparing(ToolInfo::category).thenComparing(ToolInfo::name))
                .toList();
    }

    /**
     * 转换为 Spring AI 的 ToolCallback 列表（供 Agent 使用）。
     */
    public List<ToolCallback> toToolCallbacks() {
        return tools.values().stream()
                .map(BaseTool::getName)
                .map(this::nameToCallback)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 按名称白名单转换为 ToolCallback 列表（未知名称会被忽略）。
     */
    public List<ToolCallback> toToolCallbacks(Collection<String> names) {
        return names.stream()
                .distinct()
                .map(this::nameToCallback)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 工具名称 → ToolCallback 适配。
     * 通过反射调用工具的 invoke 方法。
     */
    private ToolCallback nameToCallback(String name) {
        BaseTool tool = tools.get(name);
        if (tool == null) return null;
        return new WrappedToolCallback(tool);
    }

    public record ToolInfo(String name, String description, String category) {
    }
}