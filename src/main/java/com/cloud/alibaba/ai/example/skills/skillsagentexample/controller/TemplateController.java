package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.PromptTemplateManager;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.PromptTemplateVersion;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.PromptTemplateVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt 模板管理 REST API。
 * <p>
 * 提供模板的查看、编辑、版本管理和恢复功能，前端通过此接口在网页上进行操作。
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final PromptTemplateManager templateManager;
    private final PromptTemplateVersionService versionService;

    public TemplateController(PromptTemplateManager templateManager,
                              PromptTemplateVersionService versionService) {
        this.templateManager = templateManager;
        this.versionService = versionService;
    }

    /**
     * 获取所有模板列表（当前激活版本）。
     */
    @GetMapping
    public ResponseEntity<List<TemplateInfo>> listTemplates() {
        List<TemplateInfo> list = templateManager.getAllTemplateNames().stream()
            .map(name -> {
                PromptTemplateVersion active = versionService.getActiveVersion(name);
                String version = active != null ? active.getVersion() : "unknown";
                return new TemplateInfo(name, version, active != null ? active.getDescription() : "");
            })
            .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    /**
     * 获取指定模板的当前内容。
     */
    @GetMapping("/{name}")
    public ResponseEntity<TemplateDetail> getTemplate(@PathVariable String name) {
        PromptTemplateVersion active = versionService.getActiveVersion(name);
        if (active == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new TemplateDetail(
            active.getId(),
            active.getTemplateName(),
            active.getVersion(),
            active.getContent(),
            active.getDescription(),
            active.getCreatedAt(),
            active.isActive()
        ));
    }

    /**
     * 获取指定模板的版本历史。
     */
    @GetMapping("/{name}/history")
    public ResponseEntity<List<VersionInfo>> getVersionHistory(@PathVariable String name) {
        List<VersionInfo> history = versionService.getHistory(name).stream()
            .map(v -> new VersionInfo(v.getId(), v.getVersion(), v.getDescription(),
                v.getCreatedAt(), v.isActive()))
            .collect(Collectors.toList());
        return ResponseEntity.ok(history);
    }

    /**
     * 更新模板内容（保存为新版本）。
     */
    @PutMapping("/{name}")
    public ResponseEntity<TemplateDetail> updateTemplate(
            @PathVariable String name,
            @RequestBody UpdateTemplateRequest request) {

        String content = request.content();
        String description = request.description();

        // 确保 content 包含版本注释
        String version = request.version();
        if (content != null && !content.startsWith("<!-- version:")) {
            content = "<!-- version: " + version + " -->\n" + content;
        }

        PromptTemplateVersion saved = versionService.save(name, version, content, description);
        templateManager.reload(); // 热加载

        return ResponseEntity.ok(new TemplateDetail(
            saved.getId(),
            saved.getTemplateName(),
            saved.getVersion(),
            saved.getContent(),
            saved.getDescription(),
            saved.getCreatedAt(),
            saved.isActive()
        ));
    }

    /**
     * 恢复到指定版本。
     */
    @PostMapping("/{name}/restore/{versionId}")
    public ResponseEntity<TemplateDetail> restoreVersion(
            @PathVariable String name,
            @PathVariable String versionId) {

        versionService.activateVersion(versionId);
        templateManager.reload();

        PromptTemplateVersion active = versionService.getActiveVersion(name);
        return ResponseEntity.ok(new TemplateDetail(
            active.getId(),
            active.getTemplateName(),
            active.getVersion(),
            active.getContent(),
            active.getDescription(),
            active.getCreatedAt(),
            active.isActive()
        ));
    }

    // DTOs
    public record TemplateInfo(String name, String version, String description) {}

    public record TemplateDetail(
        String id,
        String name,
        String version,
        String content,
        String description,
        java.time.LocalDateTime createdAt,
        boolean active
    ) {}

    public record UpdateTemplateRequest(
        String content,
        String description,
        String version
    ) {}

    public record VersionInfo(
        String id,
        String version,
        String description,
        java.time.LocalDateTime createdAt,
        boolean active
    ) {}
}