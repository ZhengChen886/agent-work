package com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HITL 审批请求管理器：创建请求 → 阻塞等待 → 决策唤醒 / 超时拒绝。
 *
 * <p>与 {@link ProcessLogCollector} 复用同一 SSE 通道：审批请求与决策
 * 分别以 {@code HITL_REQUEST} / {@code HITL_DECISION} 轨迹条目推送前端，
 * {@code toolArgs} 中的 {@code _hitlRequestId} / {@code _hitlTimeoutAt}
 * 为前端渲染审批按钮所需的保留元数据键，同时随 trace_logs 持久化留档。</p>
 *
 * <p>每条请求同时落库 hitl_requests（PENDING → APPROVED/REJECTED/TIMEOUT），
 * 内部任务每分钟兜底清理超期孤儿 PENDING 行。</p>
 */
@Component
public class HitlManager {

    private static final Logger log = LoggerFactory.getLogger(HitlManager.class);

    /** toolArgs 中的保留键：审批请求 ID（前端据此回传决策）。 */
    public static final String META_REQUEST_ID = "_hitlRequestId";
    /** toolArgs 中的保留键：审批截止时间（epoch ms）。 */
    public static final String META_TIMEOUT_AT = "_hitlTimeoutAt";

    /** Agent 线程消失后，PENDING 行额外保留的兜底清理宽限期。 */
    private static final long ORPHAN_GRACE_SECONDS = 60;

    private final HitlRequestRepository repository;
    private final HitlProperties properties;
    private final ProcessLogCollector collector;
    private final ObjectMapper objectMapper;

    /** requestId → 在途审批（future + 路由信息）。 */
    private final Map<String, PendingEntry> pending = new ConcurrentHashMap<>();

    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hitl-sweeper");
                t.setDaemon(true);
                return t;
            });

    private record PendingEntry(CompletableFuture<HitlDecision> future,
                                String sessionId, String toolName) {}

    public HitlManager(HitlRequestRepository repository,
                       HitlProperties properties,
                       ProcessLogCollector collector,
                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.collector = collector;
        this.objectMapper = objectMapper;
        sweeper.scheduleAtFixedRate(this::expireOrphanPending,
                60, 60, TimeUnit.SECONDS);
    }

    /**
     * 创建审批请求：落库 → 注册 future → 广播 HITL_REQUEST 轨迹条目。
     *
     * @return 已持久化的请求（状态 PENDING）
     */
    public HitlRequest createRequest(String sessionId, String toolName,
                                     String argsJson, String agentName) {
        String id = UUID.randomUUID().toString();
        HitlRequest request = repository.save(new HitlRequest(
                id, sessionId, toolName, argsJson,
                "工具调用需人工审批"));
        pending.put(id, new PendingEntry(new CompletableFuture<>(), sessionId, toolName));

        long timeoutAt = System.currentTimeMillis()
                + Duration.ofSeconds(properties.getTimeoutSeconds()).toMillis();
        Map<String, Object> argsWithMeta = new LinkedHashMap<>(parseArgs(argsJson));
        argsWithMeta.put(META_REQUEST_ID, id);
        argsWithMeta.put(META_TIMEOUT_AT, timeoutAt);
        collector.append(sessionId, new ProcessLogEntry(
                "HITL_REQUEST", agentName,
                "⏸ 工具 " + toolName + " 待人工审批",
                toolName, argsWithMeta, null, null, HitlRequest.STATUS_PENDING,
                LocalDateTime.now(), 0L));
        log.info("[hitl] request created: id={}, session={}, tool={}", id, sessionId, toolName);
        return request;
    }

    /**
     * 阻塞等待人工决策。超时 / 中断 / 会话清理均返回 empty（fail-safe 拒绝）。
     *
     * <p>pending 未命中（决策先于等待到达的竞态窗口）时回查 DB 终态：
     * 已 APPROVED/REJECTED 的请求恢复等效决策，避免等待方误判为超时。</p>
     */
    public Optional<HitlDecision> awaitDecision(String requestId, Duration timeout) {
        PendingEntry entry = pending.get(requestId);
        if (entry == null) {
            return recoverDecisionFromDb(requestId);
        }
        try {
            HitlDecision decision = entry.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(decision);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finalizeStatus(requestId, HitlRequest.STATUS_TIMEOUT, null, "等待被中断");
            return Optional.empty();
        } catch (Exception e) {
            finalizeStatus(requestId, HitlRequest.STATUS_TIMEOUT, null, "等待异常: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 人工决策入口（{@code POST /api/hitl/requests/{id}/decide}）。
     *
     * @return 决策后的请求；empty 表示请求不存在或已被处理
     */
    public Optional<HitlRequest> respond(String requestId, HitlDecision decision) {
        Optional<HitlRequest> found = repository.findById(requestId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        HitlRequest request = found.get();
        if (!request.isPending()) {
            return Optional.empty();
        }
        PendingEntry entry = pending.remove(requestId);
        long waitedMs = request.getCreatedAt() == null ? 0
                : Duration.between(request.getCreatedAt(), LocalDateTime.now()).toMillis();

        request.setStatus(decision.action() == HitlDecision.Action.APPROVE
                ? HitlRequest.STATUS_APPROVED : HitlRequest.STATUS_REJECTED);
        request.setResponder(decision.responder());
        request.setNote(decision.note());
        request.setRespondedAt(decision.decidedAt());
        repository.save(request);

        if (entry != null) {
            entry.future().complete(decision);
        }
        broadcastDecision(request, decision, waitedMs);
        log.info("[hitl] decision: id={}, action={}, by={}", requestId, decision.action(), decision.responder());
        return Optional.of(request);
    }

    /** 会话删除联动：清理 DB 行，并以 REJECT 决策唤醒仍阻塞的 Agent 线程。 */
    public void cleanupSession(String sessionId) {
        if (sessionId == null) return;
        try {
            repository.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("[hitl] cleanup session {} failed: {}", sessionId, e.getMessage());
        }
        pending.entrySet().removeIf(e -> {
            if (sessionId.equals(e.getValue().sessionId())) {
                e.getValue().future().complete(HitlDecision.reject("system", "会话已删除，审批自动拒绝"));
                return true;
            }
            return false;
        });
    }

    /** 当前在途（等待人工决策）的请求数，供监控。 */
    public long pendingCount() {
        return pending.size();
    }

    /** 竞态兜底：决策先于 awaitDecision 到达（pending 已移除）时，从 DB 终态恢复等效决策。 */
    private Optional<HitlDecision> recoverDecisionFromDb(String requestId) {
        return repository.findById(requestId).flatMap(request -> {
            if (HitlRequest.STATUS_APPROVED.equals(request.getStatus())) {
                return Optional.of(HitlDecision.approve(request.getResponder(), request.getNote()));
            }
            if (HitlRequest.STATUS_REJECTED.equals(request.getStatus())) {
                return Optional.of(HitlDecision.reject(request.getResponder(), request.getNote()));
            }
            return Optional.empty();
        });
    }

    /** 超期未决策且 Agent 线程已消失的孤儿请求 → 标记 TIMEOUT 并唤醒残留 future。 */
    void expireOrphanPending() {
        try {
            LocalDateTime cutoff = LocalDateTime.now()
                    .minusSeconds(properties.getTimeoutSeconds() + ORPHAN_GRACE_SECONDS);
            List<HitlRequest> orphans =
                    repository.findByStatusAndCreatedAtBefore(HitlRequest.STATUS_PENDING, cutoff);
            for (HitlRequest request : orphans) {
                finalizeStatus(request.getId(), HitlRequest.STATUS_TIMEOUT, null,
                        "审批超时（" + properties.getTimeoutSeconds() + "s）");
            }
        } catch (Exception e) {
            log.warn("[hitl] sweeper failed: {}", e.getMessage());
        }
    }

    /** 幂等收尾：仅当仍为 PENDING 时迁移状态并广播；唤醒残留 future。 */
    private void finalizeStatus(String requestId, String status, String responder, String note) {
        try {
            Optional<HitlRequest> found = repository.findById(requestId);
            if (found.isPresent() && found.get().isPending()) {
                HitlRequest request = found.get();
                long waitedMs = request.getCreatedAt() == null ? 0
                        : Duration.between(request.getCreatedAt(), LocalDateTime.now()).toMillis();
                request.setStatus(status);
                request.setResponder(responder);
                request.setNote(note);
                request.setRespondedAt(LocalDateTime.now());
                repository.save(request);
                broadcastDecision(request, null, waitedMs);
            }
        } catch (Exception e) {
            log.warn("[hitl] finalize {} failed: {}", requestId, e.getMessage());
        } finally {
            PendingEntry entry = pending.remove(requestId);
            if (entry != null) {
                // null 决策 → awaitDecision 视为超时/失败
                entry.future().complete(null);
            }
        }
    }

    private void broadcastDecision(HitlRequest request, HitlDecision decision, long waitedMs) {
        try {
            String status = request.getStatus();
            String message;
            if (HitlRequest.STATUS_APPROVED.equals(status)) {
                message = "✅ 已批准" + byWhom(decision) + "，继续执行 " + request.getToolName();
            } else if (HitlRequest.STATUS_REJECTED.equals(status)) {
                message = "⛔ 已拒绝" + byWhom(decision)
                        + (blankToEmpty(request.getNote()).isEmpty() ? "" : "：" + request.getNote());
            } else {
                message = "⏱ 审批超时，按拒绝处理：" + request.getToolName();
            }
            collector.append(request.getSessionId(), new ProcessLogEntry(
                    "HITL_DECISION", null, message,
                    request.getToolName(),
                    Map.<String, Object>of(META_REQUEST_ID, request.getId()),
                    request.getNote(), waitedMs, status,
                    LocalDateTime.now(), 0L));
        } catch (Exception e) {
            log.warn("[hitl] broadcast decision failed: {}", e.getMessage());
        }
    }

    private static String byWhom(HitlDecision decision) {
        String who = decision == null ? null : decision.responder();
        return who == null || who.isBlank() ? "" : "（by " + who + "）";
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 工具入参 JSON → Map；解析失败时保留原文，便于审批人查看。 */
    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("raw", argsJson);
            return raw;
        }
    }

    @PreDestroy
    public void shutdown() {
        sweeper.shutdownNow();
        pending.values().forEach(e -> e.future().complete(null));
        pending.clear();
    }
}
