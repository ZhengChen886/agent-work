package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Conversation;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.ConversationRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.MessageRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ProcessLogCollector processLogCollector;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               ProcessLogCollector processLogCollector) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.processLogCollector = processLogCollector;
    }

    @Transactional
    public Conversation create(String initialTitle) {
        Conversation conv = new Conversation(initialTitle);
        Conversation saved = conversationRepository.save(conv);
        log.info("Created conversation: {}", saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Conversation> list() {
        return conversationRepository.findAllByOrderByLastActiveAtDesc();
    }

    @Transactional(readOnly = true)
    public Conversation get(String id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + id));
    }

    @Transactional
    public void delete(String id) {
        if (!conversationRepository.existsById(id)) {
            throw new NoSuchElementException("Conversation not found: " + id);
        }
        // 通过 conversationRepository 删除时，由于级联设置，messages 会一起删
        conversationRepository.deleteById(id);
        processLogCollector.clear(id);
        log.info("Deleted conversation: {}", id);
    }

    @Transactional
    public Conversation touch(String id) {
        Conversation conv = get(id);
        conv.touch();
        conversationRepository.save(conv);
        return conv;
    }

    @Transactional
    public void updateTitle(String id, String title) {
        Conversation conv = get(id);
        conv.setTitle(title);
        conversationRepository.save(conv);
    }

    @Transactional
    public void updateTitleIfEmpty(Conversation conv) {
        if (conv.getTitle() == null || conv.getTitle().isBlank()) {
            // 用 id 前 8 位作为默认标题
            conv.setTitle("会话 " + conv.getId().substring(0, Math.min(8, conv.getId().length())));
            conversationRepository.save(conv);
        }
    }

    @Transactional
    public void touch(Conversation conv) {
        conv.touch();
        conversationRepository.save(conv);
    }
}
