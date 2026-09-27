package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Conversation;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Message;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Role;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.MessageRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话消息持久化服务。
 *
 * <p>独立成 Bean 是为了让 {@link Transactional} 真正通过 Spring 拦截器生效
 * (在同一 controller 内部 {@code this.method()} 不会走代理)。</p>
 */
@Service
public class MessagePersistService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistService.class);

    private final MessageRepository messageRepository;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    public MessagePersistService(MessageRepository messageRepository,
                                 ConversationService conversationService,
                                 ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 持久化普通聊天:user + assistant 两条消息 + 更新会话。
     * assistant 消息带上 meta.processLogs（本轮轨迹快照），使历史回放也能查看 ReAct 思考过程。
     * 全部走事务,任意步骤失败回滚,避免 user 已写但 assistant 缺失的孤儿消息。
     */
    @Transactional
    public void persistChat(Conversation conv, String userInput, String assistantReply,
                            List<ProcessLogEntry> roundLogs) {
        try {
            messageRepository.save(new Message(conv, Role.USER, userInput));
            String meta = buildProcessLogsMeta(roundLogs, false);
            if (assistantReply != null && !assistantReply.isEmpty()) {
                messageRepository.save(new Message(conv, Role.ASSISTANT, assistantReply, meta));
            }
            conv.touch();
            conversationService.updateTitleIfEmpty(conv);
            conversationService.touch(conv.getId());
        } catch (Exception e) {
            log.error("persistChat failed", e);
            throw new IllegalStateException("persistChat failed", e);
        }
    }

    /**
     * 持久化工作流:user + assistant(meta 含 processLogs) + 更新会话。
     */
    @Transactional
    public void persistWorkflow(Conversation conv, String userText, String assistantReply,
                                List<ProcessLogEntry> roundLogs) {
        try {
            messageRepository.save(new Message(conv, Role.USER, userText));
            String meta = buildProcessLogsMeta(roundLogs, true);
            messageRepository.save(new Message(conv, Role.ASSISTANT,
                    assistantReply == null ? "" : assistantReply, meta));
            conv.touch();
            conversationService.updateTitleIfEmpty(conv);
            conversationService.touch(conv.getId());
        } catch (Exception e) {
            log.error("persistWorkflow failed", e);
            throw new IllegalStateException("persistWorkflow failed", e);
        }
    }

    /** 把本轮过程条目序列化到消息 meta（无条目时返回 null）。 */
    private String buildProcessLogsMeta(List<ProcessLogEntry> entries, boolean workflowRun) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> meta = workflowRun
                    ? Map.of("workflowRun", true, "processLogs", entries)
                    : Map.of("processLogs", entries);
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            log.warn("Serialize processLogs failed: {}", e.getMessage());
            return null;
        }
    }
}