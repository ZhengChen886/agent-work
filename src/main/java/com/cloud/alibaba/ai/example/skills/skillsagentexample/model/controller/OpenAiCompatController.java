package com.cloud.alibaba.ai.example.skills.skillsagentexample.model.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.entity.ModelProvider;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.model.service.ModelProviderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.*;

@RestController
public class OpenAiCompatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatController.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final ModelProviderService providerService;

    public OpenAiCompatController(ModelProviderService providerService) {
        this.providerService = providerService;
    }

    // ==================== GET /v1/models ====================

    @GetMapping("/v1/models")
    public Map<String, Object> listModels() {
        ModelProvider active = providerService.getActiveProvider();
        List<String> models = active.getAvailableModels();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String m : models) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m);
            item.put("object", "model");
            item.put("owned_by", active.getName());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "list");
        body.put("data", items);
        return body;
    }

    // ==================== POST /v1/chat/completions（支持标准 stream 分支） ====================

    @PostMapping("/v1/chat/completions")
    public Object createCompletion(@RequestBody Map<String, Object> rawRequest) {
        boolean stream = Boolean.TRUE.equals(rawRequest.get("stream"));
        return stream
                ? createCompletionStreamBody(rawRequest)
                : createCompletionSyncBody(rawRequest);
    }

    @PostMapping(value = "/v1/chat/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> createCompletionStream(@RequestBody Map<String, Object> rawRequest) {
        return createCompletionStreamBody(rawRequest);
    }

    private Map<String, Object> createCompletionSyncBody(Map<String, Object> rawRequest) {
        String model = resolveModel(rawRequest);
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) rawRequest.getOrDefault("messages", List.of());
        Double temperature = readDouble(rawRequest, "temperature");
        Integer maxTokens = readInteger(rawRequest, "max_tokens");

        List<Message> springMessages = toSpringMessages(messages);
        Prompt prompt = buildPrompt(springMessages, temperature, maxTokens);

        ModelProvider active;
        try {
            active = providerService.getActiveProvider();
        } catch (NoSuchElementException e) {
            return errorBody(503, "No active model provider configured");
        }
        ChatModel chatModel = buildChatModel(active);

        long requestId = System.currentTimeMillis();

        try {
            ChatResponse response = chatModel.call(prompt);
            AssistantMessage assistantMsg = (AssistantMessage) response.getResult().getOutput();
            String text = assistantMsg.getText() == null ? "" : assistantMsg.getText();

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "assistant");
            msg.put("content", text);
            choice.put("message", msg);
            choice.put("finish_reason", "stop");

            int promptTokens = estimateTokens(springMessages);
            int completionTokens = estimateTokens(text);
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", promptTokens);
            usage.put("completion_tokens", completionTokens);
            usage.put("total_tokens", promptTokens + completionTokens);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", "chatcmpl-" + requestId);
            body.put("object", "chat.completion");
            body.put("created", Instant.now().getEpochSecond());
            body.put("model", model);
            body.put("choices", List.of(choice));
            body.put("usage", usage);
            return body;
        } catch (Exception e) {
            log.error("Chat completion failed", e);
            return errorBody(500, e.getMessage());
        }
    }

    private Flux<ServerSentEvent<String>> createCompletionStreamBody(Map<String, Object> rawRequest) {
        String model = resolveModel(rawRequest);
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) rawRequest.getOrDefault("messages", List.of());
        Double temperature = readDouble(rawRequest, "temperature");
        Integer maxTokens = readInteger(rawRequest, "max_tokens");

        List<Message> springMessages = toSpringMessages(messages);
        Prompt prompt = buildPrompt(springMessages, temperature, maxTokens);

        ModelProvider active;
        try {
            active = providerService.getActiveProvider();
        } catch (NoSuchElementException e) {
            return Flux.just(ServerSentEvent.<String>builder(
                    serialize(errorBody(503, "No active model provider configured"))).build());
        }
        ChatModel chatModel = buildChatModel(active);

        long requestId = System.currentTimeMillis();

        return chatModel.stream(prompt)
                .map(resp -> {
                    AssistantMessage asm = (AssistantMessage) resp.getResult().getOutput();
                    String text = asm.getText() == null ? "" : asm.getText();

                    Map<String, Object> delta = new LinkedHashMap<>();
                    delta.put("role", "assistant");
                    delta.put("content", text);

                    Map<String, Object> choice = new LinkedHashMap<>();
                    choice.put("index", 0);
                    choice.put("delta", delta);
                    choice.put("finish_reason", null);

                    Map<String, Object> chunk = new LinkedHashMap<>();
                    chunk.put("id", "chatcmpl-" + requestId);
                    chunk.put("object", "chat.completion.chunk");
                    chunk.put("created", Instant.now().getEpochSecond());
                    chunk.put("model", model);
                    chunk.put("choices", List.of(choice));

                    return ServerSentEvent.<String>builder(serialize(chunk)).build();
                })
                .concatWith(Flux.defer(() -> {
                    Map<String, Object> doneChunk = new LinkedHashMap<>();
                    doneChunk.put("id", "chatcmpl-" + requestId);
                    doneChunk.put("object", "chat.completion.chunk");
                    doneChunk.put("created", Instant.now().getEpochSecond());
                    doneChunk.put("model", model);
                    Map<String, Object> finishChoice = new LinkedHashMap<>();
                    finishChoice.put("index", 0);
                    finishChoice.put("delta", Map.of());
                    finishChoice.put("finish_reason", "stop");
                    doneChunk.put("choices", List.of(finishChoice));
                    return Flux.just(ServerSentEvent.<String>builder(serialize(doneChunk)).build());
                }))
                .onErrorResume(e -> {
                    log.error("Stream error", e);
                    Map<String, Object> errChunk = new LinkedHashMap<>();
                    errChunk.put("error", Map.of("message", e.getMessage(), "type", "server_error"));
                    return Flux.just(ServerSentEvent.<String>builder(serialize(errChunk)).build());
                });
    }

    // ==================== 私有辅助 ====================

    private Map<String, Object> errorBody(int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", Map.of(
                "message", message == null ? "" : message,
                "code", code,
                "type", "server_error"
        ));
        return err;
    }

    private ChatModel buildChatModel(ModelProvider provider) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .apiKey(provider.getApiKey())
                        .baseUrl(provider.resolveApiBase())
                        .completionsPath("/chat/completions")
                        .embeddingsPath("/embeddings")
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(provider.getDefaultModel())
                        .build())
                .build();
    }

    private String resolveModel(Map<String, Object> req) {
        Object m = req.get("model");
        if (m instanceof String s && !s.isBlank()) return s;
        return providerService.getActiveProviderOpt()
                .map(ModelProvider::getDefaultModel)
                .orElse(null);
    }

    private List<Message> toSpringMessages(List<Map<String, Object>> rawMessages) {
        List<Message> result = new ArrayList<>();
        for (Map<String, Object> msg : rawMessages) {
            String role = String.valueOf(msg.getOrDefault("role", "user"));
            Object content = msg.get("content");
            if (content == null) continue;
            if ("system".equalsIgnoreCase(role)) {
                result.add(new SystemMessage(String.valueOf(content)));
            } else if ("assistant".equalsIgnoreCase(role)) {
                result.add(new AssistantMessage(String.valueOf(content)));
            } else {
                result.add(new UserMessage(String.valueOf(content)));
            }
        }
        return result;
    }

    private Prompt buildPrompt(List<Message> messages, Double temperature, Integer maxTokens) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (temperature != null) builder.temperature(temperature);
        if (maxTokens != null) builder.maxTokens(maxTokens);
        return new Prompt(messages, builder.build());
    }

    private int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += estimateTokens(m.getText());
        }
        return Math.max(total, 1);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Math.ceil(text.length() * 0.25);
    }

    private Double readDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    private Integer readInteger(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    private String serialize(Object obj) {
        try {
            return OM.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
