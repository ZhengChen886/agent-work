package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Conversation;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Message;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.MessageRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageRepository messageRepository;

    public ConversationController(ConversationService conversationService,
                                  MessageRepository messageRepository) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
    }

    @PostMapping
    public ResponseEntity<Conversation> create(@RequestBody(required = false) Map<String, String> body) {
        String title = (body != null) ? body.get("title") : null;
        Conversation conv = conversationService.create(title);
        return ResponseEntity.ok(conv);
    }

    @GetMapping
    public ResponseEntity<List<Conversation>> list() {
        return ResponseEntity.ok(conversationService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        Conversation conv = conversationService.get(id);
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(id);
        // 为了让 messages 里 role/content/meta/createdAt 都能正确序列化，我们手动组装
        List<Map<String, Object>> msgView = messages.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("role", m.getRole().name().toLowerCase());
            map.put("content", m.getContent());
            map.put("meta", m.getMeta());
            map.put("createdAt", m.getCreatedAt());
            return map;
        }).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("conversation", conv);
        result.put("messages", msgView);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        conversationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
