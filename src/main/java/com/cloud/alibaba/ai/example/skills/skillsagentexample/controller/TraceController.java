package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.TraceLogEntry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.TraceLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * trace 查询 API：读取 SQLite 中保留 7 天内的调用链数据。
 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private static final int MAX_SESSIONS = 100;

    private final TraceLogRepository repository;

    public TraceController(TraceLogRepository repository) {
        this.repository = repository;
    }

    /** 最近有 trace 记录的会话列表（最新优先，最多 100 个）。 */
    @GetMapping("/sessions")
    public List<TraceLogRepository.TraceSessionSummary> sessions() {
        List<TraceLogRepository.TraceSessionSummary> all = repository.findSessionSummaries();
        return all.size() > MAX_SESSIONS ? all.subList(0, MAX_SESSIONS) : all;
    }

    /** 单个会话的完整 trace 明细（时间升序）。 */
    @GetMapping("/{sessionId}")
    public List<TraceLogEntry> bySession(@PathVariable String sessionId) {
        return repository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }
}
