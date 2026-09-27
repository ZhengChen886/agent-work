package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板管理器。
 * <p>
 * 从 classpath:prompts/ 目录下加载所有 .md 模板文件，每个模板以文件名（不含扩展名）作为 key。
 * 模板文件首行可包含版本注释 {@code <!-- version: 1.0.0 -->}，会被解析为版本号。
 * <p>
 * 设计目标：把硬编码在 Java 源码中的 Prompt 抽离到外部资源文件，
 * 实现 Prompt 的可版本化、可评审、可独立修改（无需重新编译）。
 */
@Component
public class PromptTemplateManager {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateManager.class);

    private static final String PROMPTS_DIR = "prompts/";
    private static final String VERSION_PREFIX = "<!-- version:";

    /** 模板内容缓存：key=模板名, value=模板对象 */
    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadAllTemplates();
    }

    /**
     * 获取所有已加载模板的名称列表。
     */
    public List<String> getAllTemplateNames() {
        return new ArrayList<>(templates.keySet());
    }

    /**
     * 获取指定名称的模板。
     *
     * @param name 模板名称（不含 .md 扩展名）
     * @return 模板对象，不存在时返回 null
     */
    public PromptTemplate getTemplate(String name) {
        PromptTemplate tpl = templates.get(name);
        if (tpl == null) {
            log.warn("Prompt template not found: {}", name);
        }
        return tpl;
    }

    /**
     * 获取模板内容，模板不存在时返回 fallback。
     */
    public String getContentOrDefault(String name, String fallback) {
        PromptTemplate tpl = templates.get(name);
        return tpl != null ? tpl.content() : fallback;
    }

    /**
     * 返回所有已加载模板的只读快照（名称 -> 版本）。
     */
    public Map<String, String> listVersions() {
        Map<String, String> result = new HashMap<>();
        templates.forEach((k, v) -> result.put(k, v.version()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 重新加载所有模板（用于热更新场景）。
     */
    public synchronized void reload() {
        templates.clear();
        loadAllTemplates();
        log.info("Prompt templates reloaded, total: {}", templates.size());
    }

    private void loadAllTemplates() {
        String[] knownTemplates = {
            "agent-system-prompt",
            "semantic-interpretation",
            "context-summary"
        };
        for (String name : knownTemplates) {
            loadTemplate(name);
        }
        log.info("Loaded {} prompt templates", templates.size());
    }

    private void loadTemplate(String name) {
        String path = PROMPTS_DIR + name + ".md";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.debug("Prompt template file not found (skipped): {}", path);
                return;
            }
            StringBuilder sb = new StringBuilder();
            String version = "unknown";
            boolean firstLine = true;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (firstLine) {
                        firstLine = false;
                        String trimmed = line.trim();
                        if (trimmed.startsWith(VERSION_PREFIX)) {
                            int end = trimmed.indexOf("-->");
                            if (end > VERSION_PREFIX.length()) {
                                version = trimmed.substring(VERSION_PREFIX.length(), end).trim();
                            }
                            continue; // 版本行不进入模板内容
                        }
                    }
                    sb.append(line).append("\n");
                }
            }
            String content = sb.toString().stripTrailing();
            templates.put(name, new PromptTemplate(name, version, content));
            log.info("Loaded prompt template: {} (version: {})", name, version);
        } catch (Exception e) {
            log.error("Failed to load prompt template: {}", path, e);
        }
    }

    /**
     * Prompt 模板记录。
     *
     * @param name    模板名称
     * @param version 版本号
     * @param content 模板内容
     */
    public record PromptTemplate(String name, String version, String content) {
    }
}
