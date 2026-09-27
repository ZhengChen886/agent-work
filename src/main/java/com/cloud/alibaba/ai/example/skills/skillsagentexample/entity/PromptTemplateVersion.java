package com.cloud.alibaba.ai.example.skills.skillsagentexample.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/**
 * Prompt 模板版本实体。
 * <p>
 * 每个模板的每次修改都会生成一条新版本记录，支持：
 * <ul>
 *   <li>版本历史追溯</li>
 *   <li>版本回滚（恢复到任意历史版本）</li>
 *   <li>当前激活版本标记</li>
 * </ul>
 */
@Entity
@Table(name = "prompt_template_versions")
public class PromptTemplateVersion {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String templateName;

    @Column(length = 32, nullable = false)
    private String version;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(length = 512)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private Long lockVersion;

    public PromptTemplateVersion() {
    }

    public PromptTemplateVersion(String id, String templateName, String version,
                                  String content, String description) {
        this.id = id;
        this.templateName = templateName;
        this.version = version;
        this.content = content;
        this.description = description;
        this.createdAt = LocalDateTime.now();
        this.active = true;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }
}