package com.cloud.alibaba.ai.example.skills.skillsagentexample.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * trace_logs 表实体：{@code ProcessLogEntry} 的持久化形态。
 *
 * <p>由 {@code ProcessLogCollector#append} 单点写入，默认保留 7 天，
 * 超期由清理任务删除；删除会话时随 {@code clear(sessionId)} 同步删除。</p>
 */
@Entity
@Table(name = "trace_logs", indexes = {
        @Index(name = "idx_trace_logs_session", columnList = "session_id, created_at"),
        @Index(name = "idx_trace_logs_created", columnList = "created_at")
})
public class TraceLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(length = 32, nullable = false)
    private String type;

    @Column(length = 64)
    private String agent;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "tool_args", columnDefinition = "TEXT")
    private String toolArgs;

    @Column(name = "tool_result", columnDefinition = "TEXT")
    private String toolResult;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(length = 20)
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public TraceLogEntry() {}

    public TraceLogEntry(String sessionId, String type, String agent, String message,
                         String toolName, String toolArgs, String toolResult,
                         Long durationMs, String status) {
        this.sessionId = sessionId;
        this.type = type;
        this.agent = agent;
        this.message = message;
        this.toolName = toolName;
        this.toolArgs = toolArgs;
        this.toolResult = toolResult;
        this.durationMs = durationMs;
        this.status = status;
    }

    public Long getId() { return id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getToolArgs() { return toolArgs; }
    public void setToolArgs(String toolArgs) { this.toolArgs = toolArgs; }

    public String getToolResult() { return toolResult; }
    public void setToolResult(String toolResult) { this.toolResult = toolResult; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
