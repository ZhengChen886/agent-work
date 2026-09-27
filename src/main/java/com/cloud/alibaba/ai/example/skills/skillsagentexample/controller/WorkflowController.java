package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Conversation;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Message;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Role;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.MessageRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.ConversationService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.MessagePersistService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.WorkflowService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 多 Agent 工作流 REST API。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);
    private final ObjectMapper sseMapper;

    private final WorkflowService workflowService;
    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final ProcessLogCollector processLogCollector;
    private final MessagePersistService messagePersistService;

    public WorkflowController(WorkflowService workflowService,
                              ConversationService conversationService,
                              MessageRepository messageRepository,
                              ProcessLogCollector processLogCollector,
                              MessagePersistService messagePersistService,
                              @Autowired ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.processLogCollector = processLogCollector;
        this.messagePersistService = messagePersistService;
        this.sseMapper = objectMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return workflowService.listWorkflows().stream()
                .map(w -> Map.<String, Object>of(
                        "name", w.name(),
                        "description", w.description() == null ? "" : w.description(),
                        "nodes", w.nodes() == null ? List.of() : w.nodes(),
                        "edges", w.edges() == null ? List.of() : w.edges()))
                .toList();
    }

    @GetMapping("/agents")
    public List<WorkflowService.AgentSummary> agents() {
        return workflowService.listAgents();
    }

    @GetMapping("/{name}")
    public WorkflowDefinition get(@PathVariable String name) {
        return workflowService.getWorkflow(name)
                .orElseThrow(() -> new NoSuchElementException("工作流不存在: " + name));
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        workflowService.reload();
        return Map.of("ok", true);
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody WorkflowDefinition wf) {
        workflowService.saveWorkflow(wf);
        return Map.of("ok", true, "name", wf.name());
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        workflowService.deleteWorkflow(name);
        return Map.of("ok", true);
    }

    /**
     * 流式执行工作流。每次运行 = 一个会话：
     * <ul>
     *   <li>request.conversationId 为空 → 自动新建会话；</li>
     *   <li>否则复用已有会话（不存在则自动新建）。</li>
     * </ul>
     * SSE 流中既推送 assistant 累积文本，也推送 process_log 过程事件；
     * 运行结束后，把用户/助手消息以及完整过程日志写入会话。
     */
    @PostMapping(value = "/{name}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> run(@PathVariable String name,
            @RequestBody(required = false) RunRequest request) {
        String text = request == null || request.message() == null ? "" : request.message();
        log.info("Run workflow '{}' with input length={}", name, text.length());

        // 1) 解析/创建会话
        Conversation conv;
        String requestedId = request == null ? null : request.conversationId();
        try {
            conv = (requestedId != null && !requestedId.isBlank())
                    ? conversationService.get(requestedId)
                    : conversationService.create(null);
        } catch (NoSuchElementException e) {
            conv = conversationService.create(null);
        }
        final Conversation convRef = conv;
        final String sessionId = convRef.getId();

        // 2) 准备过程日志
        processLogCollector.start(sessionId);
        // 已有缓冲（例如重连）一并保留；新事件订阅只推增量
        List<ProcessLogEntry> initialBuffer =
                List.copyOf(processLogCollector.getEntries(sessionId));
        final List<ProcessLogEntry> initialSnapshot = initialBuffer;
        final java.util.concurrent.atomic.AtomicInteger seq =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<String> fullRef =
                new java.util.concurrent.atomic.AtomicReference<>("");

        try {

            // 3) 主对话流（token 即推送累积全文）
            Flux<String> tokenEvents = workflowService.runStream(name, text, sessionId)
                    .map(m -> m.getText() == null ? "" : m.getText())
                    .filter(s -> !s.isEmpty())
                    .doOnNext(token -> fullRef.updateAndGet(prev -> prev + token))
                    .map(t -> sseEvent("assistant", Map.of("content", fullRef.get(),
                            "workflow", name, "conv_id", sessionId)));

            // 4) 过程日志流（基于订阅）
            Flux<String> logEvents = Flux.<ProcessLogEntry>create(sink -> {
                // 先回放已有快照
                for (ProcessLogEntry e : initialSnapshot) {
                    sink.next(e);
                }
                processLogCollector.subscribe(sessionId, entry -> {
                    if (!sink.isCancelled()) sink.next(entry);
                });
                sink.onCancel(() -> processLogCollector.finish(sessionId));
            })
            .map(entry -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "process_log");
                m.put("seq", seq.incrementAndGet());
                m.put("step", entry.type());
                m.put("agent", entry.agent());
                m.put("message", entry.message());
                m.put("status", entry.status());
                m.put("toolName", entry.toolName());
                m.put("toolArgs", entry.toolArgs());
                m.put("toolResult", entry.toolResult());
                m.put("durationMs", entry.durationMs());
                m.put("timestamp", entry.timestamp());
                m.put("conv_id", sessionId);
                return writeJson(m);
            });

            // 5) 收尾事件：持久化放在 doFinally（即使客户端断开也会落库）
            Flux<String> tailEvents = Flux.just(
                    sseEvent("finish", Map.of("answer", fullRef.get(),
                            "workflow", name, "conv_id", sessionId)),
                    sseEvent("done", Map.of("workflow", name, "conv_id", sessionId)));

            return Flux.merge(tokenEvents, logEvents)
                    .concatWith(tailEvents)
                    .doFinally(sig -> {
                        try {
                            java.util.List<ProcessLogEntry> roundLogs =
                                    java.util.List.copyOf(processLogCollector.getRoundEntries(sessionId));
                            messagePersistService.persistWorkflow(convRef, text, fullRef.get(), roundLogs);
                        } catch (Exception ex) {
                            log.warn("Workflow finalize persist failed", ex);
                        } finally {
                            processLogCollector.finish(sessionId);
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("Workflow run error", e);
                        return Flux.just(
                                sseEvent("error", Map.of("error", e.getMessage(),
                                        "conv_id", sessionId)),
                                sseEvent("done", Map.of("workflow", name, "conv_id", sessionId)));
                    });
        } catch (Exception e) {
            log.error("Workflow run failed before streaming", e);
            try {
                java.util.List<ProcessLogEntry> roundLogs =
                        java.util.List.copyOf(processLogCollector.getRoundEntries(sessionId));
                messagePersistService.persistWorkflow(convRef, text, fullRef.get(), roundLogs);
            } catch (Exception ex) {
                log.warn("Workflow finalize persist failed (sync error path)", ex);
            }
            processLogCollector.finish(sessionId);
            return Flux.just(
                    sseEvent("error", Map.of("error", e.getMessage())),
                    sseEvent("done", Map.of("workflow", name)));
        }
    }

    /**
     * 把用户输入 + 助手回复写入会话，并把完整的过程日志 JSON 序列化到 assistant 消息的 meta 字段，
     * 便于在历史会话里也能查看 ReAct 决策过程。
     * (持久化逻辑已迁移到 {@link MessagePersistService#persistWorkflow},事务由 Proxy 真正生效)
     */

    private String sseEvent(String type, Map<String, Object> payload) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            if (payload != null) m.putAll(payload);
            return sseMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String writeJson(Object m) {
        try {
            return sseMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"error\":\"serialize failed: " + e.getClass().getSimpleName() + "\"}";
        }
    }

    public record RunRequest(String message, String conversationId) {
    }
}