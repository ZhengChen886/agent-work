package com.cloud.alibaba.ai.example.skills.skillsagentexample.trace;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.TraceLogEntry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.TraceLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 全局 ReAct 可观测条目收集器。
 *
 * <p>按 sessionId(一般与会话 ID 对齐)维度缓冲,
 * Hook/Interceptor 写入条目,控制器订阅条目以推到 SSE。</p>
 *
 * <p><b>持久化</b>(自切片 3):每次 {@link #append} 单点落库 SQLite(trace_logs 表),
 * 与内存缓冲无关;默认保留 {@code trace-retention-days}(7 天),
 * 由内部清理任务随内存 TTL 一起删除。写库失败仅告警,不影响主链路。</p>
 *
 * <p><b>TTL 清理</b>(自切片 2):默认每 1 小时扫描一次,
 * 删除最近一次写入超过 {@code retention-hours}(默认 24 小时)的会话缓冲,
 * 避免长期累积。</p>
 */
@Component
public class ProcessLogCollector {

    private static final Logger log = LoggerFactory.getLogger(ProcessLogCollector.class);

    @Value("${agent.debug.trace-retention-hours:24}")
    private long retentionHours;

    @Value("${agent.debug.trace-retention-days:7}")
    private long retentionDays;

    private final TraceLogRepository traceLogRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, List<ProcessLogEntry>> entries = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastWriteAtMs = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<ProcessLogEntry>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();
    private final Map<String, Long> roundBaseSeq = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "process-log-cleanup");
                t.setDaemon(true);
                return t;
            });

    public ProcessLogCollector(TraceLogRepository traceLogRepository, ObjectMapper objectMapper) {
        this.traceLogRepository = traceLogRepository;
        this.objectMapper = objectMapper;
        // 启动清理任务;初始延迟 60s,之后每 1h 扫描一次
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired,
                60, 3600, TimeUnit.SECONDS);
    }

    public void start(String sessionId) {
        entries.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        lastWriteAtMs.computeIfAbsent(sessionId, k -> new AtomicLong(System.currentTimeMillis()));
        AtomicLong counter = seqCounters.computeIfAbsent(sessionId, k -> new AtomicLong(0));
        // 本轮起始序号 = 当前会话累计序号 + 1（下一条 append 会从该值开始编号）
        roundBaseSeq.put(sessionId, counter.get() + 1);
        log.debug("ProcessLog session started: {}", sessionId);
    }

    public void append(String sessionId, ProcessLogEntry entry) {
        if (sessionId == null) return;
        ProcessLogEntry seqEntry = entry.withSeq(
                seqCounters.computeIfAbsent(sessionId, k -> new AtomicLong(0)).incrementAndGet());
        List<ProcessLogEntry> buffer = entries.get(sessionId);
        if (buffer != null) {
            buffer.add(seqEntry);
        }
        AtomicLong ts = lastWriteAtMs.get(sessionId);
        if (ts != null) ts.set(System.currentTimeMillis());

        persist(sessionId, seqEntry);

        List<Consumer<ProcessLogEntry>> subs = subscribers.get(sessionId);
        if (subs != null) {
            for (Consumer<ProcessLogEntry> sub : subs) {
                try {
                    sub.accept(seqEntry);
                } catch (Exception e) {
                    log.warn("ProcessLog subscriber failed: {}", e.getMessage());
                }
            }
        }
    }

    public List<ProcessLogEntry> getEntries(String sessionId) {
        return entries.getOrDefault(sessionId, List.of());
    }

    /** 只返回"本轮"（自最近一次 {@link #start} 起）追加的条目，用于消息级轨迹，避免混入历史轮次。 */
    public List<ProcessLogEntry> getRoundEntries(String sessionId) {
        long base = roundBaseSeq.getOrDefault(sessionId, 1L);
        return entries.getOrDefault(sessionId, List.of()).stream()
                .filter(e -> e.seq() >= base)
                .toList();
    }

    public void subscribe(String sessionId, Consumer<ProcessLogEntry> consumer) {
        subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    public void finish(String sessionId) {
        // 清空订阅,保留条目供后续展示(后续 TTL 行为)
        if (sessionId != null) {
            subscribers.remove(sessionId);
        }
    }

    /** 清空一个会话的日志(例如主动删除会话时调用),同时删除其持久化 trace。 */
    public void clear(String sessionId) {
        if (sessionId != null) {
            entries.remove(sessionId);
            subscribers.remove(sessionId);
            lastWriteAtMs.remove(sessionId);
            seqCounters.remove(sessionId);
            roundBaseSeq.remove(sessionId);
            try {
                int removed = traceLogRepository.deleteBySessionId(sessionId);
                log.debug("ProcessLog DB rows removed with session {}: {}", sessionId, removed);
            } catch (Exception e) {
                log.warn("ProcessLog DB delete failed for session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * TTL 清理:删除所有超过 retention-hours 未写入的会话缓冲,
     * 同时删除 DB 中超过 trace-retention-days 的历史 trace。
     * 由内部调度线程定期触发。
     */
    void cleanupExpired() {
        try {
            long now = System.currentTimeMillis();
            long maxAgeMs = TimeUnit.HOURS.toMillis(retentionHours);
            int before = entries.size();
            entries.keySet().removeIf(id -> {
                AtomicLong ts = lastWriteAtMs.get(id);
                long lastWrite = ts == null ? 0 : ts.get();
                return now - lastWrite > maxAgeMs;
            });
            int removed = before - entries.size();
            if (removed > 0) {
                // 同步清理相关索引
                lastWriteAtMs.keySet().retainAll(entries.keySet());
                seqCounters.keySet().retainAll(entries.keySet());
                roundBaseSeq.keySet().retainAll(entries.keySet());
                log.info("ProcessLog TTL cleanup: removed {} expired sessions, remaining={}",
                        removed, entries.size());
            }
            cleanupExpiredDbRows();
        } catch (Exception e) {
            log.error("ProcessLog cleanup failed", e);
        }
    }

    private void cleanupExpiredDbRows() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int removedRows = traceLogRepository.deleteCreatedBefore(cutoff);
            if (removedRows > 0) {
                log.info("TraceLog DB cleanup: removed {} rows older than {} days", removedRows, retentionDays);
            }
        } catch (Exception e) {
            log.warn("TraceLog DB cleanup failed: {}", e.getMessage());
        }
    }

    /** 单点持久化:写库失败只告警,绝不影响 SSE/内存主链路。 */
    private void persist(String sessionId, ProcessLogEntry entry) {
        try {
            String toolArgsJson = entry.toolArgs() == null
                    ? null : objectMapper.writeValueAsString(entry.toolArgs());
            traceLogRepository.save(new TraceLogEntry(
                    sessionId, entry.type(), entry.agent(), entry.message(),
                    entry.toolName(), toolArgsJson, entry.toolResult(),
                    entry.durationMs(), entry.status()));
        } catch (Exception e) {
            log.warn("TraceLog persist failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdownNow();
        try {
            if (!cleanupExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("ProcessLog cleanup executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 当前在内存中的日志会话数(供监控/测试使用)。 */
    public int size() {
        return entries.size();
    }
}