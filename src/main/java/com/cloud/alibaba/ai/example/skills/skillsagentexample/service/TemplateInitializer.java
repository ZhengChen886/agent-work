package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.PromptTemplateManager;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.PromptTemplateVersion;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.PromptTemplateVersionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 模板初始化服务，在应用启动时将模板保存到数据库。
 */
@Service
public class TemplateInitializer {

    private static final Logger log = LoggerFactory.getLogger(TemplateInitializer.class);

    private final PromptTemplateManager templateManager;
    private final PromptTemplateVersionRepository repository;

    public TemplateInitializer(PromptTemplateManager templateManager,
                              PromptTemplateVersionRepository repository) {
        this.templateManager = templateManager;
        this.repository = repository;
    }

    @PostConstruct
    public void initializeTemplates() {
        log.info("开始初始化模板到数据库...");
        
        List<String> templateNames = templateManager.getAllTemplateNames();
        
        for (String templateName : templateNames) {
            // 检查是否已经存在模板
            List<PromptTemplateVersion> existingVersions = repository.findByTemplateNameOrderByCreatedAtDesc(templateName);
            
            if (existingVersions.isEmpty()) {
                // 如果不存在，创建新版本
                createInitialTemplateVersion(templateName);
            } else {
                // 如果存在，确保有激活版本
                boolean hasActiveVersion = existingVersions.stream()
                    .anyMatch(PromptTemplateVersion::isActive);
                
                if (!hasActiveVersion) {
                    // 激活最新版本
                    PromptTemplateVersion latest = existingVersions.get(0);
                    latest.setActive(true);
                    repository.save(latest);
                    log.info("激活模板 {} 的版本 {}", templateName, latest.getVersion());
                }
            }
        }
        
        log.info("模板初始化完成，共处理 {} 个模板", templateNames.size());
    }

    private void createInitialTemplateVersion(String templateName) {
        var template = templateManager.getTemplate(templateName);
        if (template == null) {
            log.warn("模板 {} 未找到，跳过初始化", templateName);
            return;
        }

        // 创建初始版本
        PromptTemplateVersion initialVersion = new PromptTemplateVersion(
            UUID.randomUUID().toString(),
            templateName,
            template.version(),
            template.content(),
            "初始版本 - 从文件加载"
        );
        initialVersion.setCreatedAt(LocalDateTime.now());
        initialVersion.setActive(true);

        repository.save(initialVersion);
        log.info("创建模板 {} 的初始版本 {} (从文件加载)", 
            templateName, template.version());
    }
}