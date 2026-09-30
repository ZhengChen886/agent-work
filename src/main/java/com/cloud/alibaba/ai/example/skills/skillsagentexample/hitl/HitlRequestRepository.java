package com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface HitlRequestRepository extends JpaRepository<HitlRequest, String> {

    List<HitlRequest> findByStatusOrderByCreatedAtDesc(String status);

    List<HitlRequest> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** 兜底清理：找出超期未决策的 PENDING 行（Agent 线程已消失的孤儿请求）。 */
    List<HitlRequest> findByStatusAndCreatedAtBefore(String status, LocalDateTime cutoff);

    /** 删除会话时同步清理该会话的全部审批请求。 */
    @Transactional
    @Modifying
    @Query("delete from HitlRequest h where h.sessionId = :sessionId")
    int deleteBySessionId(String sessionId);
}
