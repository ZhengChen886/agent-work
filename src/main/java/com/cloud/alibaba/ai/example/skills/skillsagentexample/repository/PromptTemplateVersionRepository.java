package com.cloud.alibaba.ai.example.skills.skillsagentexample.repository;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.PromptTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromptTemplateVersionRepository extends JpaRepository<PromptTemplateVersion, String> {

    List<PromptTemplateVersion> findByTemplateNameOrderByCreatedAtDesc(String templateName);

    List<PromptTemplateVersion> findByTemplateNameAndActiveOrderByCreatedAtDesc(String templateName, boolean active);

    PromptTemplateVersion findTopByTemplateNameAndActiveOrderByCreatedAtDesc(String templateName, boolean active);

    PromptTemplateVersion findTopByTemplateNameOrderByCreatedAtDesc(String templateName);
}