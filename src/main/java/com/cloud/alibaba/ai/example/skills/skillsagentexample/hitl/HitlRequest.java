package com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * hitl_requests 表实体：一次待人工审批的工具调用请求。
 *
 * <p>生命周期：{@code PENDING → APPROVED / REJECTED / TIMEOUT}。
 * 由 {@link HitlManager} 单点写入与状态迁移；超期 PENDING 行由内部
 * 清理任务标记 TIMEOUT；会话删除时随 {@code cleanupSession} 同步清理。</p>
 */
@Entity
@Table(name = "hitl_requests", indexes = {
        @Index(name = "idx_hitl_session", columnList = "session_id, created_at"),
        @Index(name = "idx_hitl_status", columnList = "status, created_at")
})
public class HitlRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    /** UUID，由 {@link HitlManager} 生成。 */
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "tool_name", length = 128, nullable = false)
    private String toolName;

    /** 原始工具入参 JSON，供审批人查看实际将要执行的内容。 */
    @Column(name = "tool_args", columnDefinition = "TEXT")
    private String toolArgs;

    @Column(length = 512)
    private String reason;

    @Column(length = 16, nullable = false)
    private String status = STATUS_PENDING;

    /** 审批人标识（前端透传，无鉴权环境下仅作展示/审计参考）。 */
    @Column(length = 128)
    private String responder;

    /** 审批备注 / 拒绝理由。 */
    @Column(columnDefinition = "TEXT")
    private String note;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public HitlRequest() {}

    public HitlRequest(String id, String sessionId, String toolName, String toolArgs, String reason) {
        this.id = id;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.toolArgs = toolArgs;
        this.reason = reason;
        this.status = STATUS_PENDING;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getToolArgs() { return toolArgs; }
    public void setToolArgs(String toolArgs) { this.toolArgs = toolArgs; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResponder() { return responder; }
    public void setResponder(String responder) { this.responder = responder; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getRespondedAt() { return respondedAt; }
    public void setRespondedAt(LocalDateTime respondedAt) { this.respondedAt = respondedAt; }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }
}
