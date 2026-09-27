package com.cloud.alibaba.ai.example.skills.skillsagentexample.repository;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findAllByOrderByLastActiveAtDesc();
}
