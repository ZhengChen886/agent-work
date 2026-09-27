package com.cloud.alibaba.ai.example.skills.skillsagentexample.model.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "model_providers")
public class ModelProvider {

    private static final ObjectMapper OM = new ObjectMapper();

    @Id
    @Column(name = "provider_id", length = 64, nullable = false, unique = true)
    private String providerId;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "api_key", length = 512, nullable = false)
    private String apiKey;

    @Column(name = "base_url", length = 512, nullable = false)
    private String baseUrl;

    @Column(name = "default_model", length = 128, nullable = false)
    private String defaultModel;

    @Column(name = "available_models", columnDefinition = "TEXT")
    private String availableModelsJson;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ModelProvider() {
        this.active = false;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public ModelProvider(String providerId, String name, String apiKey,
                         String baseUrl, String defaultModel, List<String> availableModels) {
        this();
        this.providerId = providerId;
        this.name = name;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        setAvailableModels(availableModels);
    }

    @PrePersist
    @PreUpdate
    private void touch() {
        updatedAt = Instant.now();
    }

    public String getProviderId()    { return providerId; }
    public void setProviderId(String id) { this.providerId = id; }

    public String getName()    { return name; }
    public void setName(String name) { this.name = name; }

    public String getApiKey()         { return apiKey; }
    public void setApiKey(String key) { this.apiKey = key; }

    public String getBaseUrl()             { return baseUrl; }
    public void setBaseUrl(String url) { this.baseUrl = url; }

    public String getDefaultModel()           { return defaultModel; }
    public void setDefaultModel(String m) { this.defaultModel = m; }

    public String getAvailableModelsJson()          { return availableModelsJson; }
    public void setAvailableModelsJson(String json) { this.availableModelsJson = json; }

    public List<String> getAvailableModels() {
        if (availableModelsJson == null || availableModelsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> list = OM.readValue(availableModelsJson, List.class);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void setAvailableModels(List<String> models) {
        try {
            this.availableModelsJson = models != null ? OM.writeValueAsString(models) : "[]";
        } catch (Exception e) {
            this.availableModelsJson = "[]";
        }
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    /**
     * 去掉末尾斜杠后原样返回 base_url，<b>不做任何路径补全</b>。
     * <p>
     * 请用户在配置时填写包含完整版本路径的 base_url，例如：
     * <ul>
     *   <li>智谱：https://open.bigmodel.cn/api/paas/v4</li>
     *   <li>英伟达：https://integrate.api.nvidia.com/v1</li>
     *   <li>魔搭：https://api-inference.modelscope.cn/v1</li>
     * </ul>
     * chat 端点 = resolveApiBase() + "/chat/completions"
     * 模型列表    = resolveApiBase() + "/models"
     */
    public String resolveApiBase() {
        if (baseUrl == null || baseUrl.isBlank()) return "";
        return baseUrl.replaceAll("/+$", "");
    }

    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
}
