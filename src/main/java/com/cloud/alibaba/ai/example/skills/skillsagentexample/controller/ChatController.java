package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.ContextCompressor;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.PromptPreprocessor;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.PromptPreprocessorService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.SemanticInterpreter;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.SkillsAgent;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Conversation;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Message;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Role;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.MessageRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.ConversationService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.MessagePersistService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.WorkflowService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.ChatRequest;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.ChatResponse;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.SemanticInterpretation;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final SkillsAgent skillsAgent;
    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final ContextCompressor contextCompressor;
    private final PromptPreprocessorService promptPreprocessor;
    private final SemanticInterpreter semanticInterpreter;
    private final WorkflowService workflowService;
    private final ProcessLogCollector processLogCollector;
    private final MessagePersistService messagePersistService;
    private final ObjectMapper sseMapper;

    @Value("${agent.debug.enabled:false}")
    private boolean debugEnabled;

    public ChatController(SkillsAgent skillsAgent,
                          ConversationService conversationService,
                          MessageRepository messageRepository,
                          ContextCompressor contextCompressor,
                          PromptPreprocessorService promptPreprocessor,
                          SemanticInterpreter semanticInterpreter,
                          WorkflowService workflowService,
                          ProcessLogCollector processLogCollector,
                          MessagePersistService messagePersistService,
                          @Autowired ObjectMapper objectMapper) {
        this.skillsAgent = skillsAgent;
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.contextCompressor = contextCompressor;
        this.promptPreprocessor = promptPreprocessor;
        this.semanticInterpreter = semanticInterpreter;
        this.workflowService = workflowService;
        this.processLogCollector = processLogCollector;
        this.messagePersistService = messagePersistService;
        this.sseMapper = objectMapper;
    }

    // ==================== 流式接口（SSE） ====================

    /**
     * 流式多轮对话 API（Server-Sent Events，JSON 事件协议）。
     * <p>
     * 协议：每个事件是独立 SSE 帧：
     * <pre>
     *   data: {"type":"assistant","content":"&lt;累积全文&gt;"}
     *   data: {"type":"finish","answer":"&lt;最终全文&gt;"}
     *   data: {"type":"done","conv_id":"&lt;会话ID&gt;"}
     *   data: {"type":"error","error":"&lt;错误信息&gt;"}
     * </pre>
     * 前端用 {@code fetch + ReadableStream} 解析，
     * 拿到 {@code assistant} 事件后用 {@code marked.parse(content)} 整体重新渲染 Markdown，
     * 避免被切断的标签（如代码块、加粗、链接）流式渲染错乱。
     * </p>
     *
     * <p>额外支持工作流命令：消息以 {@code /workflow <name> <input>} 开头时，
     * 转发给 {@link WorkflowService} 运行指定工作流并以相同 SSE 协议输出。</p>
     */
    @PostMapping(value = "/api/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody(required = false) ChatRequest request) {
        String message = request == null ? null : request.message();

        // 工作流命令：/workflow <name> <input...>
        if (message != null && message.startsWith("/workflow ")) {
            String[] parts = message.split(" ", 3);
            if (parts.length >= 3) {
                String requestedConvId = request.conversationId();
                return streamWorkflow(parts[1], parts[2], requestedConvId);
            }
            return Flux.just(sseEvent("error", Map.of(
                    "error", "工作流命令格式错误：应为 /workflow <name> <message>")));
        }

        if (message == null || message.isBlank()) {
            return Flux.just(sseEvent("error", Map.of("error", "message 不能为空")));
        }
        return doStream(request == null ? null : request.conversationId(), message);
    }

    /** 兼容旧 GET 路径（保留向后兼容）。 */
    @GetMapping(value = "/api/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChatGet(@RequestParam(required = false) String conversationId,
                                      @RequestParam String message) {
        if (message != null && message.startsWith("/workflow ")) {
            String[] parts = message.split(" ", 3);
            if (parts.length >= 3) {
                return streamWorkflow(parts[1], parts[2], conversationId);
            }
        }
        return doStream(conversationId, message);
    }

    /**
     * 通过对话命令 {@code /workflow <name> <input>} 触发工作流。
     * 每个调用 = 一个会话（自动新建），同时推送 process_log，结束后写入会话消息。
     */
    private Flux<String> streamWorkflow(String workflowName, String input, String requestedConvId) {
        log.info("Run workflow via chat command: name={}, inputLen={}", workflowName, input.length());

        Conversation conv;
        try {
            conv = (requestedConvId != null && !requestedConvId.isBlank())
                    ? conversationService.get(requestedConvId)
                    : conversationService.create(null);
        } catch (NoSuchElementException e) {
            conv = conversationService.create(null);
        }
        final Conversation convRef = conv;
        final String sessionId = convRef.getId();

        processLogCollector.start(sessionId);
        List<ProcessLogEntry> initialSnapshot =
                List.copyOf(processLogCollector.getEntries(sessionId));
        java.util.concurrent.atomic.AtomicInteger seq =
                new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.concurrent.atomic.AtomicReference<String> fullRef =
                    new java.util.concurrent.atomic.AtomicReference<>("");
        try {
            Flux<String> events = workflowService.runStream(workflowName, input, sessionId)
                    .map(msg -> msg.getText() == null ? "" : msg.getText())
                    .filter(s -> !s.isEmpty())
                    .doOnNext(token -> fullRef.updateAndGet(prev -> prev + token))
                    .map(t -> sseEvent("assistant", Map.of(
                            "content", fullRef.get(),
                            "workflow", workflowName,
                            "conv_id", sessionId)));

            Flux<String> logEvents = Flux.<ProcessLogEntry>create(sink -> {
                for (ProcessLogEntry e : initialSnapshot) {
                    sink.next(e);
                }
                processLogCollector.subscribe(sessionId, entry -> {
                    if (!sink.isCancelled()) sink.next(entry);
                });
                sink.onCancel(() -> processLogCollector.finish(sessionId));
            }).map(entry -> {
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
                try {
                    return sseMapper.writeValueAsString(m);
                } catch (Exception ex) {
                    return "{\"type\":\"error\",\"error\":\"serialize failed\"}";
                }
            });

            Flux<String> tailEvents = Flux.just(
                        sseEvent("finish", Map.of(
                                "answer", fullRef.get(),
                                "workflow", workflowName,
                                "conv_id", sessionId)),
                        sseEvent("done", Map.of("workflow", workflowName, "conv_id", sessionId)));

            return Flux.merge(events, logEvents)
                    .concatWith(tailEvents)
                    .doFinally(sig -> {
                        try {
                            java.util.List<ProcessLogEntry> roundLogs =
                                    java.util.List.copyOf(processLogCollector.getRoundEntries(sessionId));
                            messagePersistService.persistWorkflow(convRef, input, fullRef.get(), roundLogs);
                            processLogCollector.finish(sessionId);
                        } catch (Exception ex) {
                            log.warn("Workflow chat finalize persist failed", ex);
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("Workflow run error", e);
                        processLogCollector.finish(sessionId);
                        return Flux.just(
                                sseEvent("error", Map.of("error", e.getMessage(), "conv_id", sessionId)),
                                sseEvent("done", Map.of("workflow", workflowName, "conv_id", sessionId)));
                    });
        } catch (Exception e) {
            log.error("Workflow run failed before streaming", e);
            processLogCollector.finish(sessionId);
            return Flux.just(
                    sseEvent("error", Map.of("error", e.getMessage())),
                    sseEvent("done", Map.of("workflow", workflowName)));
        }
    }

    /** 真正的流式逻辑（普通聊天）。 */
    private Flux<String> doStream(String conversationId, String message) {
        long streamStart = System.currentTimeMillis();
        // 1. 预处理 + JDBC 准备（当前线程完成，不阻塞 reactor）
        ChatPrep prep = ChatPrep.prepare(conversationId, message,
                conversationService, messageRepository, contextCompressor, promptPreprocessor, semanticInterpreter);

        String convId = prep.convRef.get().getId();

        // 统一 trace 入口：预处理事件（规则匹配/语义解读）进入 ProcessLogCollector
        processLogCollector.start(convId);
        appendPrepTrace(convId, prep);

        // 敏感词拦截：不调 AI，直接返回拦截说明
        if (prep.blocked) {
            String blockMsg = prep.blockReason;
            Conversation blockedConv = prep.convRef.get();

            return Flux.just(
                    sseEvent("assistant", Map.of("content", blockMsg)),
                    sseEvent("finish", Map.of("answer", blockMsg)),
                    sseEvent("done", Map.of("conv_id", blockedConv.getId()))
            ).doFinally(sig -> processLogCollector.finish(convId))
                    .doOnSubscribe(s -> {
                        java.util.List<ProcessLogEntry> roundLogs =
                                java.util.List.copyOf(processLogCollector.getRoundEntries(convId));
                        Mono.fromRunnable(() ->
                                messagePersistService.persistChat(blockedConv, message, blockMsg, roundLogs))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                v -> log.info("SSE blocked persist done, convId={}, replyLen={}",
                                        blockedConv.getId(),
                                        blockMsg == null ? 0 : blockMsg.length()),
                                err -> log.error("SSE blocked persist error", err));
                    });
        }

        StringBuilder full = new StringBuilder();
        log.info("SSE chat prepared, conversationId={}, historyMsgs={}", convId, prep.aiMessages.size());

        // 构建调试事件流（如果启用调试模式）
        Flux<String> debugEvents = Flux.empty();
        if (debugEnabled && prep.traceEvents != null) {
            debugEvents = Flux.fromIterable(prep.traceEvents)
                    .mapNotNull(event -> {
                        String eventType = (String) event.get("type");
                        Map<String, Object> data = (Map<String, Object>) event.get("data");
                        if (eventType == null) return null;
                        // 推送调试事件给前端
                        String sseType = "debug:" + switch (eventType) {
                            case "规则匹配" -> "rule_check";
                            case "语义解读" -> "semantic";
                            default -> eventType.toLowerCase().replace(" ", "_");
                        };
                        return sseEvent(sseType, data);
                    });
        }

        // 2. 主对话流：每个 token 即时把"累积全文"打包成 assistant 事件
        java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.FluxSink<ProcessLogEntry>> plogSink =
                new java.util.concurrent.atomic.AtomicReference<>();
        Flux<String> assistantEvents = skillsAgent.chatStream(prep.aiMessages, convId)
                .map(msg -> msg.getText() == null ? "" : msg.getText())
                .filter(text -> !text.isEmpty())
                .doOnNext(full::append)
                .map(token -> sseEvent("assistant", Map.of("content", full.toString())))
                .doOnComplete(() -> {
                    // 在 logEvents 完成前追加 CHAT_END，确保实时轨迹面板也能收到最后一条
                    processLogCollector.append(convId, ProcessLogEntry.step("CHAT_END",
                            "模型响应完成，长度=" + full.length() + "，tokens≈" + full.length() / 4,
                            System.currentTimeMillis() - streamStart, "SUCCESS"));
                    reactor.core.publisher.FluxSink<ProcessLogEntry> s = plogSink.get();
                    if (s != null) s.complete();
                });

        // 2.b 思考过程流：把 ReAct 执行轨迹（Agent/模型/工具步骤）实时推给前端
        Flux<String> logEvents = processLogEvents(convId, plogSink);

        // 3. 尾部事件：finish + done + 异步持久化 + 追踪结束
        Flux<String> tailEvents = Flux.just(
                    sseEvent("finish", Map.of("answer", full.toString())),
                    sseEvent("done", Map.of("conv_id", convId))
            )
            .doOnSubscribe(s -> {
                java.util.List<ProcessLogEntry> roundLogs =
                        java.util.List.copyOf(processLogCollector.getRoundEntries(convId));
                Mono.fromRunnable(() ->
                                messagePersistService.persistChat(
                                        prep.convRef.get(), message, full.toString(), roundLogs))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                v -> log.info("SSE chat persisted, convId={}, replyLen={}",
                                        convId, full.toString().length()),
                                err -> log.error("SSE persist error", err));
            });

        return Flux.concat(debugEvents, Flux.merge(logEvents, assistantEvents), tailEvents)
                .doOnError(err -> {
                    log.error("SSE stream error", err);
                    processLogCollector.append(convId,
                            ProcessLogEntry.error(null, SkillsAgent.describeError(err)));
                })
                .onErrorResume(err -> {
                    // 区分瞬态错误（已在 SkillsAgent 内自动重试 N 次后仍失败）与逻辑错误
                    boolean transientAfterRetries = SkillsAgent.isTransientError(err);
                    int attempts = skillsAgent.modelRetryMaxAttempts();
                    String userMsg = transientAfterRetries
                            ? "模型服务持续不可用（已自动重试 " + attempts
                                    + " 次，每次间隔 1 分钟）。请稍后再试，或检查模型供应商配置。"
                            : "Agent 运行失败：" + SkillsAgent.describeError(err);
                    log.error("SSE stream resumed with error: transient={}, attempts={}, msg={}",
                            transientAfterRetries, attempts, userMsg);
                    return Flux.just(
                            sseEvent("error", Map.of("error", userMsg, "conv_id", convId)),
                            sseEvent("done", Map.of("conv_id", convId)));
                })
                .doFinally(sig -> processLogCollector.finish(convId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 把 ProcessLogCollector 中"本轮"过程条目转换为 process_log SSE 事件流。 */
    private Flux<String> processLogEvents(String sessionId,
                                          java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.FluxSink<ProcessLogEntry>> sinkRef) {
        return Flux.<ProcessLogEntry>create(sink -> {
            sinkRef.set(sink);
            List<ProcessLogEntry> snapshot =
                    List.copyOf(processLogCollector.getRoundEntries(sessionId));
            for (ProcessLogEntry e : snapshot) {
                sink.next(e);
            }
            processLogCollector.subscribe(sessionId, entry -> {
                if (!sink.isCancelled()) sink.next(entry);
            });
        }).mapNotNull(entry -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "process_log");
            m.put("seq", entry.seq());
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
            try {
                return sseMapper.writeValueAsString(m);
            } catch (Exception ex) {
                return "{\"type\":\"error\",\"error\":\"serialize failed\"}";
            }
        });
    }

    /** 把 ChatPrep 阶段的预处理事件（规则匹配/语义解读）落入统一 trace 链路。 */
    @SuppressWarnings("unchecked")
    private void appendPrepTrace(String convId, ChatPrep prep) {
        if (prep.traceEvents == null) return;
        for (Map<String, Object> ev : prep.traceEvents) {
            String eventType = (String) ev.get("type");
            Map<String, Object> data = (Map<String, Object>) ev.get("data");
            if (eventType == null) continue;
            String entryType;
            String message;
            if ("规则匹配".equals(eventType)) {
                entryType = "RULE_CHECK";
                message = "is_command=" + data.get("is_command")
                        + ", blocked=" + data.get("is_blocked");
            } else if ("语义解读".equals(eventType)) {
                entryType = "SEMANTIC";
                message = "intent=" + data.get("intent") + ", domain=" + data.get("domain");
            } else {
                entryType = eventType.toUpperCase().replace(" ", "_");
                message = String.valueOf(data);
            }
            long durationMs = data.get("duration_ms") instanceof Number n ? n.longValue() : 0L;
            String status = Boolean.TRUE.equals(data.get("is_blocked")) ? "BLOCKED" : "SUCCESS";
            processLogCollector.append(convId,
                    ProcessLogEntry.step(entryType, message, durationMs, status));
        }
    }

    /** 把 type + payload 打包成 SSE data 行（不带额外换行，由外层拼接 \n\n）。 */
    private String sseEvent(String type, Map<String, Object> payload) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            if (payload != null) m.putAll(payload);
            // Spring MVC 的 TEXT_EVENT_STREAM 序列化器会自动加 "data: " 前缀，
            // 我们只输出裸 JSON + 行尾 \n（事件帧分隔由 Spring 加 "\n\n"）
            return sseMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"error\":\"serialize failed: " + e.getMessage() + "\"}";
        }
    }

    /** 封装流式调用前的 JDBC 准备工作。 */
    private static class ChatPrep {
        AtomicReference<Conversation> convRef;
        List<org.springframework.ai.chat.messages.Message> aiMessages;
        boolean blocked = false;
        String blockReason;
        List<Map<String, Object>> traceEvents;

        static ChatPrep prepare(String conversationId, String userInput,
                                ConversationService convSvc, MessageRepository msgRepo,
                                ContextCompressor compressor,
                                PromptPreprocessorService preprocessor,
                                SemanticInterpreter interpreter) {
            List<Map<String, Object>> traceEvents = new ArrayList<>();
            
            // 1. 第一步：正则/规则匹配（敏感词、命令路由、实体提取）
            long startTime = System.currentTimeMillis();
            PromptPreprocessor.ProcessResult preResult = preprocessor.process(userInput);
            long ruleDuration = System.currentTimeMillis() - startTime;
            
            traceEvents.add(Map.of(
                "type", "规则匹配",
                "data", Map.of(
                    "duration_ms", ruleDuration,
                    "is_command", preResult.isCommand(),
                    "is_blocked", preResult.isBlocked(),
                    "command_aliases", preResult.commandAliases() != null ? 
                        String.join(",", preResult.commandAliases()) : ""
                )
            ));
            
            List<String> systemContext = new ArrayList<>(preprocessor.appendSystemContext(userInput, preResult));
            if (preResult.isBlocked()) {
                log.info("Prompt blocked: {}", preResult.blockReason());
                Conversation conv = convSvc.create(null);
                ChatPrep p = new ChatPrep();
                p.convRef = new AtomicReference<>(conv);
                p.aiMessages = List.of(new SystemMessage(preResult.blockReason()));
                p.blocked = true;
                p.blockReason = preResult.blockReason();
                p.traceEvents = traceEvents;
                return p;
            }

            // 2. 第二步：正则未命中命令时，用小模型做语义解读
            if (!preResult.isCommand()) {
                long semanticStart = System.currentTimeMillis();
                SemanticInterpretation interp = interpreter.interpret(userInput);
                long semanticDuration = System.currentTimeMillis() - semanticStart;
                
                if (interp.success()) {
                    systemContext.add(interp.contextText());
                    traceEvents.add(Map.of(
                        "type", "语义解读",
                        "data", Map.of(
                            "duration_ms", semanticDuration,
                            "intent", interp.intent(),
                            "domain", interp.domain(),
                            "slots", interp.slots()
                        )
                    ));
                    log.info("Semantic interpretation: intent={}, domain={}, slots={}",
                            interp.intent(), interp.domain(), interp.slots());
                }
            }

            if (!systemContext.isEmpty()) {
                log.info("Preprocessor added {} system context entries", systemContext.size());
            }

            // 2. 确定/创建会话
            Conversation conv;
            if (conversationId == null || conversationId.isBlank()) {
                conv = convSvc.create(null);
            } else {
                try {
                    conv = convSvc.get(conversationId);
                } catch (NoSuchElementException e) {
                    log.warn("Conversation not found: {}, creating new", conversationId);
                    conv = convSvc.create(null);
                }
            }

            // 3. 查询历史
            List<Message> history = msgRepo.findByConversationIdOrderByCreatedAtAsc(conv.getId());

            // 4. 上下文压缩
            ContextCompressor.CompressionResult cr = compressor.compress(history);
            if (cr.compressed()) {
                if (cr.toDelete() != null) msgRepo.deleteAll(cr.toDelete());
                if (cr.summaryContent() != null)
                    msgRepo.save(new Message(conv, Role.SUMMARY, cr.summaryContent()));
            }

            // 5. 组装 AI 消息
            List<Message> forAgent = cr.compressed() ? cr.messagesForAgent() : history;
            List<org.springframework.ai.chat.messages.Message> aiMsgs = new ArrayList<>();
            for (String ctx : systemContext) {
                aiMsgs.add(new SystemMessage(ctx));
            }
            for (Message m : forAgent) {
                if (m.getContent() == null) continue;
                switch (m.getRole()) {
                    case SUMMARY -> aiMsgs.add(new SystemMessage(m.getContent()));
                    case USER -> aiMsgs.add(new UserMessage(m.getContent()));
                    case ASSISTANT -> aiMsgs.add(new AssistantMessage(m.getContent()));
                }
            }
            aiMsgs.add(new UserMessage(userInput));

            ChatPrep p = new ChatPrep();
            p.convRef = new AtomicReference<>(conv);
            p.aiMessages = aiMsgs;
            p.traceEvents = traceEvents;
            return p;
        }
    }

    // ==================== 同步接口（降级/fallback） ====================

    /** 多轮对话 API（同步 POST）。可被前端在 SSE 失败时降级调用。 */
    @PostMapping("/api/chat")
    @Transactional
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("Sync chat, conversationId={}, messageLen={}",
                request.conversationId(),
                request.message() == null ? 0 : request.message().length());

        // 第一步：正则/规则匹配（敏感词、命令路由、实体提取）
        PromptPreprocessor.ProcessResult preResult = promptPreprocessor.process(request.message());
        List<String> systemContext = new ArrayList<>(promptPreprocessor.appendSystemContext(request.message(), preResult));

        // 第二步：正则未命中命令时，用小模型做语义解读
        if (!preResult.isBlocked() && !preResult.isCommand()) {
            SemanticInterpretation interp = semanticInterpreter.interpret(request.message());
            if (interp.success()) {
                systemContext.add(interp.contextText());
                log.info("Semantic interpretation: intent={}, domain={}, slots={}",
                        interp.intent(), interp.domain(), interp.slots());
            }
        }

        if (!systemContext.isEmpty()) {
            log.info("Preprocessor added {} system context entries", systemContext.size());
        }
        if (preResult.isBlocked()) {
            log.info("Sync prompt blocked: {}", preResult.blockReason());
            Conversation conv = conversationService.create(null);
            messageRepository.save(new Message(conv, Role.USER, request.message()));
            messageRepository.save(new Message(conv, Role.ASSISTANT, preResult.blockReason()));
            conv.touch();
            conversationService.updateTitleIfEmpty(conv);
            conversationService.touch(conv.getId());
            return new ChatResponse(conv.getId(), preResult.blockReason(), false);
        }

        Conversation conv;
        if (request.conversationId() == null || request.conversationId().isBlank()) {
            conv = conversationService.create(null);
        } else {
            conv = conversationService.get(request.conversationId());
        }

        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        ContextCompressor.CompressionResult cr = contextCompressor.compress(history);
        boolean compressed = cr.compressed();
        if (compressed) {
            if (cr.toDelete() != null) messageRepository.deleteAll(cr.toDelete());
            if (cr.summaryContent() != null)
                messageRepository.save(new Message(conv, Role.SUMMARY, cr.summaryContent()));
        }

        List<Message> forAgent = compressed ? cr.messagesForAgent() : history;
        List<org.springframework.ai.chat.messages.Message> aiMsgs = new ArrayList<>();
        for (String ctx : systemContext) {
            aiMsgs.add(new SystemMessage(ctx));
        }
        for (Message m : forAgent) {
            if (m.getContent() == null) continue;
            switch (m.getRole()) {
                case SUMMARY -> aiMsgs.add(new SystemMessage(m.getContent()));
                case USER -> aiMsgs.add(new UserMessage(m.getContent()));
                case ASSISTANT -> aiMsgs.add(new AssistantMessage(m.getContent()));
            }
        }
        aiMsgs.add(new UserMessage(request.message()));

        String reply;
        try {
            AssistantMessage resp = skillsAgent.chat(aiMsgs);
            reply = resp.getText();
        } catch (Exception e) {
            log.error("skillsAgent.chat failed", e);
            reply = "抱歉，AI 服务暂时无法响应：" + e.getMessage();
        }

        messageRepository.save(new Message(conv, Role.USER, request.message()));
        messageRepository.save(new Message(conv, Role.ASSISTANT, reply));
        conv.touch();
        conversationService.updateTitleIfEmpty(conv);
        conversationService.touch(conv.getId());

        return new ChatResponse(conv.getId(), reply, compressed);
    }

    /** 旧兼容接口（query param 方式）。 */
    @PostMapping("/chat")
    public String legacyChat(@RequestParam String message) {
        return chat(new ChatRequest(null, message)).reply();
    }
}
