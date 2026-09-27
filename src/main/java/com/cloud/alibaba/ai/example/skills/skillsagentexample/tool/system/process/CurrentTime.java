package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.system.process;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 获取当前时间工具。
 *
 * 参数：
 * <ul>
 *   <li>timezone: 时区（默认 Asia/Shanghai）</li>
 *   <li>format: 时间格式（默认 yyyy-MM-dd HH:mm:ss）</li>
 * </ul>
 */
@Tool(name = "system.time.now", description = "获取当前时间", category = "system.time")
public class CurrentTime extends BaseTool {

    @Override
    protected ToolResult doExecute(Map<String, Object> args) {
        String timezone = args != null && args.containsKey("timezone")
                ? (String) args.get("timezone") : "Asia/Shanghai";
        String format = args != null && args.containsKey("format")
                ? (String) args.get("format") : "yyyy-MM-dd HH:mm:ss";

        try {
            ZoneId zone = ZoneId.of(timezone);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            String formatted = formatter.format(Instant.now().atZone(zone));
            Map<String, Object> data = Map.of(
                    "timezone", timezone,
                    "format", format,
                    "current_time", formatted,
                    "timestamp", Instant.now().toEpochMilli()
            );
            return ToolResult.ok(data, 0);
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage(), 0);
        }
    }
}