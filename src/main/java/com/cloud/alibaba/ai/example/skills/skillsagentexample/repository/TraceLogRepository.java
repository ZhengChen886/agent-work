package com.cloud.alibaba.ai.example.skills.skillsagentexample.repository;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.TraceLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface TraceLogRepository extends JpaRepository<TraceLogEntry, Long> {

    List<TraceLogEntry> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 删除指定时间之前的历史 trace（7 天保留策略）。 */
    @Transactional
    @Modifying
    @Query("delete from TraceLogEntry t where t.createdAt < :cutoff")
    int deleteCreatedBefore(LocalDateTime cutoff);

    /** 删除会话时同步清理该会话的全部 trace。 */
    @Transactional
    @Modifying
    @Query("delete from TraceLogEntry t where t.sessionId = :sessionId")
    int deleteBySessionId(String sessionId);

    /** 按会话聚合的 trace 概览，按最近活动倒序。 */
    @Query("select t.sessionId as sessionId, count(t) as entries, max(t.createdAt) as lastTime "
            + "from TraceLogEntry t group by t.sessionId order by max(t.createdAt) desc")
    List<TraceSessionSummary> findSessionSummaries();

    interface TraceSessionSummary {
        String getSessionId();
        long getEntries();
        LocalDateTime getLastTime();
    }
}
