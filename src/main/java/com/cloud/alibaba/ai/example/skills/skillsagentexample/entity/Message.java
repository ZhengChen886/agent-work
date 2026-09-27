package com.cloud.alibaba.ai.example.skills.skillsagentexample.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    @JsonIgnore
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Role role;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** 自由元数据 JSON 字符串，例如工作流执行的 processLog/processLogs 列表。 */
    @Column(columnDefinition = "TEXT")
    private String meta;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Message() {}

    public Message(Conversation conversation, Role role, String content) {
        this(conversation, role, content, null);
    }

    public Message(Conversation conversation, Role role, String content, String meta) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.meta = meta;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Conversation getConversation() { return conversation; }
    public void setConversation(Conversation conversation) { this.conversation = conversation; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getMeta() { return meta; }
    public void setMeta(String meta) { this.meta = meta; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
