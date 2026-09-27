package com.cloud.alibaba.ai.example.skills.skillsagentexample.model.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.entity.ModelProvider;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.service.ModelProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/model-providers")
public class ModelProviderController {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderController.class);
    private final ModelProviderService providerService;

    public ModelProviderController(ModelProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public List<ModelProvider> list() {
        return providerService.listProviders();
    }

    @GetMapping("/{providerId}")
    public ModelProvider get(@PathVariable String providerId) {
        try {
            return providerService.getProvider(providerId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelProvider add(@RequestBody ModelProvider provider) {
        if (provider.getProviderId() == null || provider.getProviderId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerId 不能为空");
        }
        if (provider.getName() == null || provider.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 不能为空");
        }
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "apiKey 不能为空");
        }
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl 不能为空");
        }
        if (provider.getDefaultModel() == null || provider.getDefaultModel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "defaultModel 不能为空");
        }
        try {
            return providerService.addProvider(provider);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/{providerId}")
    public ModelProvider update(@PathVariable String providerId,
                                @RequestBody ModelProvider updates) {
        try {
            return providerService.updateProvider(providerId, updates);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String providerId) {
        try {
            providerService.deleteProvider(providerId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{providerId}/switch")
    public ModelProvider switchProvider(@PathVariable String providerId) {
        try {
            ModelProvider p = providerService.switchProvider(providerId);
            syncModelsInBackground(providerId);
            return p;
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{providerId}/sync-models")
    public List<String> syncModels(@PathVariable String providerId) {
        try {
            return providerService.fetchAndSyncModels(providerId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{providerId}/test")
    public Map<String, Object> testProvider(@PathVariable String providerId) {
        try {
            return providerService.testProvider(providerId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private void syncModelsInBackground(String providerId) {
        Thread.ofVirtual().start(() -> {
            try {
                providerService.fetchAndSyncModels(providerId);
            } catch (Exception ignored) {
            }
        });
    }
}
