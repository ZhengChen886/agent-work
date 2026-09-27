package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.PromptTemplateVersion;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.repository.PromptTemplateVersionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Prompt 模板版本服务，提供模板的版本管理和恢复功能。
 * <p>
 * 主要功能包括：
 * <ul>
 *   <li>保存新版本的模板</li>
 *   <li>获取指定模板的所有历史版本</li>
 *   <li>获取指定模板的当前激活版本</li>
 *   <li>通过版本ID获取特定版本的内容</li>
 *   <li>激活指定版本</li>
 *   <li>删除指定版本</li>
 * </ul>
 */
@Service
public class PromptTemplateVersionService {

    private final PromptTemplateVersionRepository repository;

    public PromptTemplateVersionService(PromptTemplateVersionRepository repository) {
        this.repository = repository;
    }

    public PromptTemplateVersion save(String templateName, String version, String content, String description) {
        // 先将当前激活版本设为不活跃
        List<PromptTemplateVersion> activeVersions =
            repository.findByTemplateNameAndActiveOrderByCreatedAtDesc(templateName, true);
        for (PromptTemplateVersion active : activeVersions) {
            active.setActive(false);
            repository.save(active);
        }

        // 保存新版本
        PromptTemplateVersion newVersion = new PromptTemplateVersion(
            UUID.randomUUID().toString(),
            templateName,
            version,
            content,
            description
        );
        return repository.save(newVersion);
    }

    public List<PromptTemplateVersion> getHistory(String templateName) {
        return repository.findByTemplateNameOrderByCreatedAtDesc(templateName);
    }

    public PromptTemplateVersion getActiveVersion(String templateName) {
        return repository.findTopByTemplateNameAndActiveOrderByCreatedAtDesc(templateName, true);
    }

    public PromptTemplateVersion getVersionById(String id) {
        return repository.findById(id).orElse(null);
    }

    public void activateVersion(String id) {
        PromptTemplateVersion version = repository.findById(id)
            .orElseThrow(() -> new RuntimeException("版本不存在：" + id));
        // 先取消所有激活版本
        List<PromptTemplateVersion> allVersions =
            repository.findByTemplateNameOrderByCreatedAtDesc(version.getTemplateName());
        for (PromptTemplateVersion v : allVersions) {
            v.setActive(false);
            repository.save(v);
        }
        // 濜活指定版本
        version.setActive(true);
        repository.save(version);
    }

    public void deleteVersion(String id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
        }
    }

    public String getContentByVersionId(String id) {
        PromptTemplateVersion version = getVersionById(id);
        return version != null ? version.getContent() : null;
    }
}
