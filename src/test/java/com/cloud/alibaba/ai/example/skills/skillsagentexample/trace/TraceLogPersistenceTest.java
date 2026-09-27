package com.cloud.alibaba.ai.example.skills.skillsagentexample.trace;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.TraceLogEntry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.TraceLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 trace 统一链路的 SQLite 持久化行为：
 * append 落库、clear 同步删库、7 天保留清理。
 *
 * <p>admin.port=0：Admin 后台绑定随机空闲端口，
 * 避免与正在运行的应用（8088）冲突导致上下文加载失败。</p>
 */
@SpringBootTest(properties = "admin.port=0")
class TraceLogPersistenceTest {

    @Autowired
    private ProcessLogCollector collector;

    @Autowired
    private TraceLogRepository repository;

    @Test
    void appendPersistsToSqlite() {
        String session = "test-trace-persist-001";
        try {
            collector.start(session);
            collector.append(session, ProcessLogEntry.agentStart("test-agent"));
            collector.append(session, ProcessLogEntry.toolCall("test-agent", "file.read",
                    Map.of("path", "a.txt"), "ok", 5, "SUCCESS"));

            List<TraceLogEntry> rows = repository.findBySessionIdOrderByCreatedAtAsc(session);
            assertEquals(2, rows.size());
            assertEquals("AGENT_START", rows.get(0).getType());
            assertEquals("TOOL_CALL", rows.get(1).getType());
            assertEquals("file.read", rows.get(1).getToolName());
            assertNotNull(rows.get(1).getToolArgs());
            assertTrue(rows.get(1).getToolArgs().contains("a.txt"));
        } finally {
            collector.clear(session);
        }
    }

    @Test
    void appendPersistsEvenWithoutStart() {
        String session = "test-trace-persist-002";
        try {
            collector.append(session, ProcessLogEntry.step("SEMANTIC", "intent=test", 3L, "SUCCESS"));

            List<TraceLogEntry> rows = repository.findBySessionIdOrderByCreatedAtAsc(session);
            assertFalse(rows.isEmpty());
            assertEquals("SEMANTIC", rows.get(0).getType());
        } finally {
            collector.clear(session);
        }
    }

    @Test
    void clearRemovesMemoryAndDbRows() {
        String session = "test-trace-persist-003";
        collector.append(session, ProcessLogEntry.error("test-agent", "boom"));
        assertFalse(repository.findBySessionIdOrderByCreatedAtAsc(session).isEmpty());

        collector.clear(session);
        assertTrue(collector.getEntries(session).isEmpty());
        assertTrue(repository.findBySessionIdOrderByCreatedAtAsc(session).isEmpty());
    }

    @Test
    void cleanupExpiredDeletesRowsOlderThanRetentionDays() {
        String session = "test-trace-persist-004";
        TraceLogEntry old = new TraceLogEntry(session, "AGENT_START", null, "expired-entry",
                null, null, null, null, null);
        old.setCreatedAt(LocalDateTime.now().minusDays(30));
        repository.save(old);
        assertFalse(repository.findBySessionIdOrderByCreatedAtAsc(session).isEmpty());

        collector.cleanupExpired();

        assertTrue(repository.findBySessionIdOrderByCreatedAtAsc(session).isEmpty());
    }
}
