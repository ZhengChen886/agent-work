package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.system.process;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统信息工具（CPU、内存、JVM 状态）。
 */
@Tool(name = "system.info", description = "获取系统与 JVM 信息", category = "system.info")
public class SystemInfo extends BaseTool {

    @Override
    protected ToolResult doExecute(Map<String, Object> args) {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> data = new HashMap<>();
        data.put("os", System.getProperty("os.name"));
        data.put("os_version", System.getProperty("os.version"));
        data.put("java_version", System.getProperty("java.version"));
        data.put("user", System.getProperty("user.name"));
        data.put("user_dir", System.getProperty("user.dir"));
        data.put("available_processors", runtime.availableProcessors());

        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        Map<String, Object> mem = new HashMap<>();
        mem.put("max_bytes", maxMem);
        mem.put("total_bytes", totalMem);
        mem.put("free_bytes", freeMem);
        mem.put("used_bytes", totalMem - freeMem);
        data.put("memory", mem);

        data.put("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());

        return ToolResult.ok(data, 0);
    }
}