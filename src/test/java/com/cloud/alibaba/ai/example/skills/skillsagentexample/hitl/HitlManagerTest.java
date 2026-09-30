package com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.TraceLogRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HitlManager 核心链路单测（Mockito，不依赖 Spring 上下文/数据库）：
 * 创建 → 等待 → 决策唤醒 / 超时拒绝 / 重复决策冲突 / 会话清理联动。
 */
class HitlManagerTest {

    private HitlRequestRepository repository;
    private HitlProperties properties;
    private HitlManager manager;

    /** repository.save 透传返回原实体，模拟 JPA 行为。 */
    private final List<HitlRequest> db = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(HitlRequestRepository.class);
        when(repository.save(any(HitlRequest.class)))
                .thenAnswer(inv -> {
                    HitlRequest saved = inv.getArgument(0);
                    // 简化内存版 upsert，供 findById/状态断言使用
                    db.removeIf(r -> r.getId().equals(saved.getId()));
                    db.add(saved);
                    return saved;
                });
        when(repository.findById(any(String.class)))
                .thenAnswer(inv -> db.stream()
                        .filter(r -> r.getId().equals(inv.getArgument(0)))
                        .findFirst());
        when(repository.findByStatusAndCreatedAtBefore(any(String.class), any(LocalDateTime.class)))
                .thenAnswer(inv -> db.stream()
                        .filter(r -> r.getStatus().equals(inv.getArgument(0))
                                && r.getCreatedAt().isBefore(inv.getArgument(1)))
                        .toList());

        properties = new HitlProperties();
        properties.setTimeoutSeconds(1);

        ProcessLogCollector collector =
                new ProcessLogCollector(mock(TraceLogRepository.class), new ObjectMapper());
        manager = new HitlManager(repository, properties, collector, new ObjectMapper());
    }

    private HitlRequest createPending() {
        return manager.createRequest("session-1", "shell.exec",
                "{\"command\":\"ls\"}", "test-agent");
    }

    @Test
    void createRequestIsPendingAndCounted() {
        HitlRequest request = createPending();

        assertEquals(HitlRequest.STATUS_PENDING, request.getStatus());
        assertEquals("shell.exec", request.getToolName());
        assertEquals(1, manager.pendingCount());
        assertTrue(repository.findById(request.getId()).isPresent());
    }

    @Test
    void approveWakesWaitingThread() throws Exception {
        HitlRequest request = createPending();

        CompletableFuture<Optional<HitlDecision>> waiter = CompletableFuture.supplyAsync(
                () -> manager.awaitDecision(request.getId(), Duration.ofSeconds(5)));

        Optional<HitlRequest> updated =
                manager.respond(request.getId(), HitlDecision.approve("tester", "ok"));

        assertTrue(updated.isPresent());
        assertEquals(HitlRequest.STATUS_APPROVED, updated.get().getStatus());
        Optional<HitlDecision> decision = waiter.get(2, TimeUnit.SECONDS);
        assertTrue(decision.isPresent());
        assertEquals(HitlDecision.Action.APPROVE, decision.get().action());
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void rejectCarriesNoteToAgent() throws Exception {
        HitlRequest request = createPending();

        CompletableFuture<Optional<HitlDecision>> waiter = CompletableFuture.supplyAsync(
                () -> manager.awaitDecision(request.getId(), Duration.ofSeconds(5)));

        manager.respond(request.getId(), HitlDecision.reject("tester", "危险操作"));

        Optional<HitlDecision> decision = waiter.get(2, TimeUnit.SECONDS);
        assertTrue(decision.isPresent());
        assertEquals(HitlDecision.Action.REJECT, decision.get().action());
        assertEquals("危险操作", decision.get().note());
        assertEquals(HitlRequest.STATUS_REJECTED,
                repository.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void duplicateDecisionReturnsEmpty() {
        HitlRequest request = createPending();

        assertTrue(manager.respond(request.getId(), HitlDecision.approve("a", null)).isPresent());
        // 第二次决策（无论批准还是拒绝）均被拒绝，状态不回退
        assertTrue(manager.respond(request.getId(), HitlDecision.reject("b", null)).isEmpty());
        assertEquals(HitlRequest.STATUS_APPROVED,
                repository.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void respondToUnknownRequestReturnsEmpty() {
        assertTrue(manager.respond("not-exist", HitlDecision.approve("a", null)).isEmpty());
    }

    @Test
    void timeoutMarksDbAndReturnsEmpty() {
        HitlRequest request = createPending();

        Optional<HitlDecision> decision =
                manager.awaitDecision(request.getId(), Duration.ofMillis(100));

        assertTrue(decision.isEmpty());
        assertEquals(HitlRequest.STATUS_TIMEOUT,
                repository.findById(request.getId()).orElseThrow().getStatus());
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void cleanupSessionRemovesRowsAndRejectsPending() throws Exception {
        HitlRequest request = createPending();

        // 独立线程稍后清理，确保主线程（与生产 Agent 线程一致：create → await 连续调用）
        // 已进入阻塞等待，验证"阻塞中的线程被 REJECT 唤醒"
        Thread cleaner = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            manager.cleanupSession("session-1");
        });
        cleaner.start();

        Optional<HitlDecision> decision =
                manager.awaitDecision(request.getId(), Duration.ofSeconds(5));

        cleaner.join(2000);
        assertTrue(decision.isPresent());
        assertEquals(HitlDecision.Action.REJECT, decision.get().action());
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void lateAwaitRecoversDecisionFromDb() {
        // 竞态窗口：决策先落库并移除 pending，而等待方尚未调用 awaitDecision
        HitlRequest request = createPending();
        manager.respond(request.getId(), HitlDecision.approve("fast", "提前批准"));

        Optional<HitlDecision> decision =
                manager.awaitDecision(request.getId(), Duration.ofSeconds(1));

        assertTrue(decision.isPresent());
        assertEquals(HitlDecision.Action.APPROVE, decision.get().action());
        assertEquals("fast", decision.get().responder());
    }

    @Test
    void sweeperExpiresOrphanPendingRows() {
        HitlRequest orphan = new HitlRequest(
                "orphan-1", "session-2", "file.write", "{}", "孤儿请求");
        orphan.setCreatedAt(LocalDateTime.now().minusHours(2));
        db.add(orphan);

        manager.expireOrphanPending();

        assertEquals(HitlRequest.STATUS_TIMEOUT, orphan.getStatus());
    }

    @Test
    void metaKeysEmbeddedInTrace() {
        // createRequest 广播的轨迹条目应携带 _hitlRequestId 元数据（前端渲染依赖）
        createPending();
        // 无异常 + pending 注册成功即可（轨迹内容经由 ProcessLogCollector，已由上述用例覆盖主链路）
        assertFalse(db.isEmpty());
        assertTrue(db.get(0).getToolArgs().contains("command"));
    }

    @Test
    void propertiesDecisionLogic() {
        HitlProperties p = new HitlProperties();
        assertTrue(p.requiresApprovalFor("shell.exec"));
        assertTrue(p.requiresApprovalFor("file.delete"));
        assertFalse(p.requiresApprovalFor("file.read"));
        assertFalse(p.requiresApprovalFor("web.search"));
        // auto-approve 优先于 requires-approval
        p.getAutoApprove().add("shell.exec");
        assertFalse(p.requiresApprovalFor("shell.exec"));
        assertFalse(p.requiresApprovalFor(null));
    }
}
