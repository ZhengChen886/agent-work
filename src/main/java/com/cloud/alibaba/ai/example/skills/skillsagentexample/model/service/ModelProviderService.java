package com.cloud.alibaba.ai.example.skills.skillsagentexample.model.service;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.entity.ModelProvider;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.repository.ModelProviderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelProviderService {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderService.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final RestTemplate REST_TEMPLATE = new RestTemplate();

    private final ModelProviderRepository repo;
    private final ConcurrentHashMap<String, ModelProvider> cache = new ConcurrentHashMap<>();

    public ModelProviderService(ModelProviderRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void init() {
        repo.findAll().forEach(p -> cache.put(p.getProviderId(), p));
        log.info("Loaded {} model providers from DB", cache.size());
        boolean hasActive = cache.values().stream().anyMatch(ModelProvider::isActive);
        if (!hasActive && !cache.isEmpty()) {
            cache.values().iterator().next().setActive(true);
            repo.save(cache.values().iterator().next());
        }
    }

    // ==================== CRUD ====================

    public List<ModelProvider> listProviders() {
        return new ArrayList<>(cache.values());
    }

    public ModelProvider getProvider(String providerId) {
        ModelProvider p = cache.get(providerId);
        if (p == null) {
            throw new NoSuchElementException("Provider not found: " + providerId);
        }
        return p;
    }

    public ModelProvider addProvider(ModelProvider provider) {
        if (cache.containsKey(provider.getProviderId())) {
            throw new IllegalArgumentException("Provider ID already exists: " + provider.getProviderId());
        }
        provider.setActive(false);
        repo.save(provider);
        cache.put(provider.getProviderId(), provider);
        log.info("Provider added: {}", provider.getProviderId());
        return provider;
    }

    public ModelProvider updateProvider(String providerId, ModelProvider updates) {
        ModelProvider existing = require(providerId);
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getApiKey() != null) existing.setApiKey(updates.getApiKey());
        if (updates.getBaseUrl() != null) existing.setBaseUrl(updates.getBaseUrl());
        if (updates.getDefaultModel() != null) existing.setDefaultModel(updates.getDefaultModel());
        if (updates.getAvailableModelsJson() != null) existing.setAvailableModelsJson(updates.getAvailableModelsJson());
        repo.save(existing);
        cache.put(providerId, existing);
        log.info("Provider updated: {}", providerId);
        return existing;
    }

    public void deleteProvider(String providerId) {
        ModelProvider p = require(providerId);
        if (p.isActive()) {
            throw new IllegalStateException("Cannot delete active provider: " + providerId);
        }
        repo.delete(p);
        cache.remove(providerId);
        log.info("Provider deleted: {}", providerId);
    }

    public ModelProvider switchProvider(String providerId) {
        ModelProvider target = require(providerId);
        repo.deactivateAll();
        // 同步更新缓存：先全部置为 inactive，再激活目标
        cache.values().forEach(p -> p.setActive(false));
        target.setActive(true);
        repo.save(target);
        cache.put(providerId, target);
        log.info("Switched active provider to: {}", providerId);
        return target;
    }

    // ==================== 连通性测试 ====================

    public Map<String, Object> testProvider(String providerId) {
        ModelProvider p = require(providerId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerId", p.getProviderId());
        result.put("baseUrl", p.getBaseUrl());
        result.put("model", p.getDefaultModel());
        try {
            // 最小化聊天请求：1 条 user message，max_tokens=1，验证模型 API 可实际对话
            long start = System.currentTimeMillis();
            String url = p.resolveApiBase() + "/chat/completions";
            String body = "{\"model\":\"" + p.getDefaultModel() + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(p.getApiKey());
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = REST_TEMPLATE.postForEntity(url, entity, String.class);
            long elapsed = System.currentTimeMillis() - start;
            result.put("success", resp.getStatusCode().is2xxSuccessful());
            result.put("status", resp.getStatusCode().value());
            result.put("elapsedMs", elapsed);
            if (resp.getStatusCode().is2xxSuccessful()) {
                try {
                    JsonNode root = OM.readTree(resp.getBody());
                    JsonNode choice = root.path("choices").path(0);
                    String content = choice.path("message").path("content").asText("");
                    result.put("message", "模型对话正常（回复: " + content + "）");
                } catch (Exception ignored) {
                    result.put("message", "模型对话正常（返回 200）");
                }
            } else {
                result.put("message", "HTTP " + resp.getStatusCode().value());
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("status", 0);
            result.put("message", "模型对话失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 活跃状态查询 ====================

    public ModelProvider getActiveProvider() {
        return cache.values().stream()
                .filter(ModelProvider::isActive)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No active model provider"));
    }

    public Optional<ModelProvider> getActiveProviderOpt() {
        return cache.values().stream().filter(ModelProvider::isActive).findFirst();
    }

    // ==================== 模型列表同步 ====================

    public List<String> fetchAndSyncModels(String providerId) {
        ModelProvider p = require(providerId);
        try {
            String url = p.resolveApiBase() + "/models";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(p.getApiKey());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> resp = REST_TEMPLATE.exchange(url, HttpMethod.GET, entity, String.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode root = OM.readTree(resp.getBody());
                JsonNode data = root.path("data");
                if (data.isArray()) {
                    List<String> models = new ArrayList<>();
                    for (JsonNode node : data) {
                        String id = node.path("id").asText("");
                        if (!id.isBlank()) models.add(id);
                    }
                    p.setAvailableModels(models);
                    repo.save(p);
                    cache.put(providerId, p);
                    log.info("Fetched {} models from provider '{}'", models.size(), providerId);
                    return models;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch models from provider '{}': {}", providerId, e.getMessage());
        }
        return List.of(p.getDefaultModel());
    }

    // ==================== 私有辅助 ====================

    private ModelProvider require(String providerId) {
        ModelProvider p = cache.get(providerId);
        if (p == null) {
            throw new NoSuchElementException("Provider not found: " + providerId);
        }
        return p;
    }
}
