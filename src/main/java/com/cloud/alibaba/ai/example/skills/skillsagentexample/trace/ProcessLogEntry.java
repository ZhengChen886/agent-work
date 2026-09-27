package com.cloud.alibaba.ai.example.skills.skillsagentexample.trace;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 单步可观测条目：Agent 启动/结束、模型调用、工具调用。
 * <p>每个条目都会作为 SSE 事件推给前端，并最终落入消息的 meta.processLogs 列表，
 * 用于在历史会话里也展示 ReAct 决策过程。</p>
 *
 * <p>{@code seq} 为会话级连续递增序号：同一会话内第 N 轮提问的轨迹，
 * 会接着第 N-1 轮继续编号（而不是每轮从 1 重新开始）。
 * 由 {@link ProcessLogCollector#append} 统一分配，工厂方法默认置 0。</p>
 */
public record ProcessLogEntry(
        String type,
        String agent,
        String message,
        String toolName,
        Map<String, Object> toolArgs,
        String toolResult,
        Long durationMs,
        String status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp,
        long seq) {

    public static ProcessLogEntry agentStart(String agent) {
        return new ProcessLogEntry("AGENT_START", agent, "▶ Agent 开始执行",
                null, null, null, null, "RUNNING", LocalDateTime.now(), 0L);
    }

    public static ProcessLogEntry agentEnd(String agent, long durationMs, String status) {
        return new ProcessLogEntry("AGENT_END", agent,
                "■ Agent 执行完成",
                null, null, null, durationMs, status, LocalDateTime.now(), 0L);
    }

    public static ProcessLogEntry modelCall(String agent, String preview) {
        return new ProcessLogEntry("MODEL_CALL", agent,
                preview == null ? "调用模型" : truncate(preview, 200),
                null, null, null, null, "RUNNING", LocalDateTime.now(), 0L);
    }

    public static ProcessLogEntry toolCall(String agent, String toolName,
                                           Map<String, Object> args,
                                           String result,
                                           long durationMs,
                                           String status) {
        return new ProcessLogEntry("TOOL_CALL", agent,
                "调用工具 " + toolName,
                toolName, args, truncate(result, 400), durationMs, status,
                LocalDateTime.now(), 0L);
    }

    public static ProcessLogEntry error(String agent, String message) {
        return new ProcessLogEntry("ERROR", agent, message,
                null, null, null, null, "FAILED", LocalDateTime.now(), 0L);
    }

    /**
     * 模型调用瞬态错误后"等 delay 后重试"的可观测条目。
     * 前端按 step == "RETRYING" 渲染：黄色高亮 + "重试中" 标签。
     *
     * @param agentName Agent 名（如 skill-agent）
     * @param attempt   第几次重试（1-based）
     * @param delaySecs 等待时长（秒）
     * @param reason    失败原因摘要（已截断）
     */
    public static ProcessLogEntry retrying(String agentName, int attempt,
                                          int delaySecs, String reason) {
        return new ProcessLogEntry("RETRYING", agentName,
                "模型响应超时/报错，正在等待 " + delaySecs + "s 后重试（第 " + attempt + " 次）： " + reason,
                null, null, null, null, "RUNNING", LocalDateTime.now(), 0L);
    }

    /** 通用步骤条目（规则匹配、语义解读、聊天结束等非 Agent/工具事件）。 */
    public static ProcessLogEntry step(String type, String message, Long durationMs, String status) {
        return new ProcessLogEntry(type, null, message,
                null, null, null, durationMs, status, LocalDateTime.now(), 0L);
    }

    /** 返回一份携带指定连续序号的副本（其余字段不变）。 */
    public ProcessLogEntry withSeq(long seq) {
        return new ProcessLogEntry(type, agent, message, toolName, toolArgs,
                toolResult, durationMs, status, timestamp, seq);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}