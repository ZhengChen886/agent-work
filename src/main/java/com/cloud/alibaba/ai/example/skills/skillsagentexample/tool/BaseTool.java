package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 所有工具的基类。
 *
 * 提供统一的执行流程：
 * <ol>
 *   <li>参数校验（子类实现 {@link #validate})</li>
 *   <li>执行（子类实现 {@link #doExecute}）</li>
 *   <li>异常捕获 → 统一返回 {@link ToolResult}</li>
 *   <li>过程追踪（conversationId 非空时写入 {@link ProcessLogCollector}，随 SSE 推送并持久化）</li>
 *   <li>日志记录</li>
 * </ol>
 *
 * 子类只需要：
 * <pre>
 *   &#64;Tool(name = "file.read", description = "读取文件", category = "core.file")
 *   public class ReadFile extends BaseTool {
 *       &#64;Override protected void validate(Map<String, Object> args) { ... }
 *       &#64;Override protected ToolResult doExecute(Map<String, Object> args) { ... }
 *   }
 * </pre>
 */
public abstract class BaseTool {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected ProcessLogCollector processLogCollector;

    public String getName() {
        Tool annotation = getClass().getAnnotation(Tool.class);
        return annotation != null ? annotation.name() : getClass().getSimpleName();
    }

    public String getDescription() {
        Tool annotation = getClass().getAnnotation(Tool.class);
        return annotation != null ? annotation.description() : "";
    }

    public String getCategory() {
        Tool annotation = getClass().getAnnotation(Tool.class);
        return annotation != null ? annotation.category() : "default";
    }

    public boolean isEnabled() {
        Tool annotation = getClass().getAnnotation(Tool.class);
        return annotation == null || annotation.enabled();
    }

    /**
     * 统一执行入口。
     */
    public final ToolResult invoke(String conversationId, Map<String, Object> args) {
        long startTime = System.currentTimeMillis();
        String toolName = getName();
        log.debug("Tool [{}] invoked with args: {}", toolName, args);

        try {
            validate(args);
            ToolResult result = doExecute(args);
            long durationMs = System.currentTimeMillis() - startTime;
            result = new ToolResult(result.success(), result.data(), result.error(), durationMs);

            recordTrace(conversationId, toolName, args,
                    result.data() == null ? null : String.valueOf(result.data()),
                    durationMs, result.success() ? "SUCCESS" : "FAILED");
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Tool [{}] failed: {}", toolName, e.getMessage(), e);
            recordTrace(conversationId, toolName, args, e.getMessage(), durationMs, "FAILED");
            return ToolResult.fail(e.getMessage(), durationMs);
        }
    }

    /**
     * 参数校验（子类可重写）。
     */
    protected void validate(Map<String, Object> args) throws IllegalArgumentException {
    }

    /**
     * 实际执行逻辑（子类必须实现）。
     */
    protected abstract ToolResult doExecute(Map<String, Object> args) throws Exception;

    /**
     * 过程追踪：conversationId 为空（如 WrappedToolCallback 场景）时跳过，
     * 追踪失败不影响工具执行结果。
     */
    private void recordTrace(String conversationId, String toolName,
                             Map<String, Object> args, String result,
                             long durationMs, String status) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        try {
            processLogCollector.append(conversationId,
                    ProcessLogEntry.toolCall(null, toolName, args, result, durationMs, status));
        } catch (Exception e) {
            log.debug("Tool trace append failed: {}", e.getMessage());
        }
    }
}
