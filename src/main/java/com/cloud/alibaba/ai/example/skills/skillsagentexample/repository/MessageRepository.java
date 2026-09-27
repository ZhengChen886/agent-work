package com.cloud.alibaba.ai.example.skills.skillsagentexample.repository;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    void deleteByConversationId(String conversationId);
}
