package com.cloud.alibaba.ai.example.skills.skillsagentexample.admin;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.SkillService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolRegistry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 后台管理 API 聚合。
 *
 * <p>统一暴露在 {@code /api/admin/} 路径下，包含：</p>
 * <ul>
 *   <li>GET /api/admin/overview         — 总览（系统状态、统计指标）</li>
 *   <li>GET /api/admin/tools            — 工具列表（代理）</li>
 *   <li>GET /api/admin/metrics          — Prometheus 指标快照</li>
 *   <li>GET /api/admin/jvm              — JVM 详细信息</li>
 *   <li>GET /api/admin/config           — 当前生效配置</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(prefix = "admin", name = "enabled", havingValue = "true")
public class AdminController {

    private final ToolRegistry toolRegistry;
    private final MeterRegistry meterRegistry;
    private final SkillService skillService;

    @Value("${spring.ai.openai.base-url:}")
    private String openaiBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String openaiModel;

    @Value("${agent.debug.enabled:false}")
    private boolean debugEnabled;

    @Autowired
    public AdminController(ToolRegistry toolRegistry, MeterRegistry meterRegistry,
                           SkillService skillService) {
        this.toolRegistry = toolRegistry;
        this.meterRegistry = meterRegistry;
        this.skillService = skillService;
    }

    /** 总览：把常用信息聚合成一次接口 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app_name", "Skills Agent Example");
        result.put("jvm", jvmInfo());
        result.put("tools", Map.of(
                "count", toolRegistry.getAllToolNames().size(),
                "categories", toolRegistry.getCategories().size()));
        result.put("config", Map.of(
                "debug_enabled", debugEnabled,
                "openai_base_url", openaiBaseUrl,
                "openai_model", openaiModel));
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /** 所有工具（含分类） */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", toolRegistry.getAllToolNames().size());
        result.put("tools", toolRegistry.getToolInfos());
        result.put("by_category", toolRegistry.getToolsByCategory());
        return result;
    }

    /** Micrometer 指标快照 */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> meters = meterRegistry.getMeters().stream()
                .map(m -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", m.getId().getName());
                    info.put("type", m.getId().getType());
                    info.put("tags", m.getId().getTags());
                    if (m instanceof io.micrometer.core.instrument.Counter counter) {
                        info.put("value", counter.count());
                    } else if (m instanceof io.micrometer.core.instrument.Timer timer) {
                        info.put("count", timer.count());
                        info.put("mean_ms", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
                    } else if (m instanceof io.micrometer.core.instrument.Gauge gauge) {
                        info.put("value", gauge.value());
                    }
                    return info;
                })
                .collect(Collectors.toList());
        result.put("count", meters.size());
        result.put("meters", meters);
        return result;
    }

    /** JVM 详细信息 */
    @GetMapping("/jvm")
    public Map<String, Object> jvm() {
        return jvmInfo();
    }

    /** 当前生效的应用配置 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("debug_enabled", debugEnabled);
        result.put("openai_base_url", openaiBaseUrl);
        result.put("openai_model", openaiModel);
        result.put("active_profiles", System.getProperty("spring.profiles.active", "default"));
        return result;
    }

    /** 远程调用工具（Admin 后台直接 invoke，便于测试） */
    @GetMapping("/tools/test")
    public ToolResult testTool(String name, Map<String, Object> args) {
        Map<String, Object> realArgs = args == null ? Map.of() : args;
        return toolRegistry.invoke(name, "admin-test", realArgs);
    }

    /** 工具详情 */
    @GetMapping("/tools/info")
    public Map<String, Object> toolInfo(String name) {
        return toolRegistry.getTool(name)
                .map(t -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", t.getName());
                    info.put("description", t.getDescription());
                    info.put("category", t.getCategory());
                    info.put("enabled", t.isEnabled());
                    info.put("class", t.getClass().getName());
                    return info;
                })
                .orElse(Map.of("error", "Tool not found: " + name));
    }

    /** 调用工具（POST 形式，支持复杂参数） */
    @PostMapping("/tools/invoke")
    public ToolResult invokeTool(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) body.getOrDefault("args", Map.of());
        return toolRegistry.invoke(name, "admin-test", args);
    }

    /** 技能列表 */
    @GetMapping("/skills")
    public Object skills() {
        return skillService.list();
    }

    private Map<String, Object> jvmInfo() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("max_mb", runtime.maxMemory() / 1024 / 1024);
        mem.put("total_mb", runtime.totalMemory() / 1024 / 1024);
        mem.put("free_mb", runtime.freeMemory() / 1024 / 1024);
        mem.put("used_mb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("java_version", System.getProperty("java.version"));
        jvm.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        jvm.put("processors", runtime.availableProcessors());
        jvm.put("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
        jvm.put("memory", mem);
        return jvm;
    }
}