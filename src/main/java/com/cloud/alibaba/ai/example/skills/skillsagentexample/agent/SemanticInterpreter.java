package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.SemanticInterpretation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * 第二步：小模型语义解读。
 * 当正则/规则未能命中（未被拦截、未路由到命令）时，用小模型做意图分类、领域识别、槽位提取。
 * <p>
 * 系统提示词从 {@link PromptTemplateManager} 加载（模板名：semantic-interpretation），
 * 模板不存在时降级为硬编码的默认提示词。
 */
@Component
public class SemanticInterpreter {

    private static final Logger log = LoggerFactory.getLogger(SemanticInterpreter.class);

    /** 模板加载失败时的降级提示词 */
    private static final String FALLBACK_SYSTEM_PROMPT = """
            你是轻量级语义解读器。请分析用户输入，仅输出一个 JSON 对象，不要输出任何其他内容。
            JSON 格式：
            {"intent":"chat|knowledge|code|other","domain":"general|medical|legal|finance","slots":["实体1","实体2"]}
            """;

    private final ChatModel chatModel;
    private final PromptTemplateManager promptTemplateManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SemanticInterpreter(ChatModel chatModel, PromptTemplateManager promptTemplateManager) {
        this.chatModel = chatModel;
        this.promptTemplateManager = promptTemplateManager;
    }

    public SemanticInterpretation interpret(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return SemanticInterpretation.failed();
        }
        try {
            String systemPrompt = promptTemplateManager.getContentOrDefault(
                    "semantic-interpretation", FALLBACK_SYSTEM_PROMPT);

            List<org.springframework.ai.chat.messages.Message> messages = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(prompt)
            );

            AssistantMessage reply = (AssistantMessage) chatModel.call(new Prompt(messages))
                    .getResult()
                    .getOutput();

            String text = reply.getText();
            if (text == null || text.isBlank()) {
                return SemanticInterpretation.failed();
            }

            JsonNode node = parseJson(text);
            String intent = node != null && node.hasNonNull("intent") ? node.get("intent").asText() : "other";
            String domain = node != null && node.hasNonNull("domain") ? node.get("domain").asText() : "general";
            List<String> slots = new ArrayList<>();
            if (node != null && node.has("slots") && node.get("slots").isArray()) {
                for (JsonNode s : node.get("slots")) {
                    slots.add(s.asText());
                }
            }

            log.debug("Small model interpretation: intent={}, domain={}, slots={}", intent, domain, slots);
            return new SemanticInterpretation(intent, domain, slots, true, text);
        } catch (Exception e) {
            log.warn("Small model interpretation failed, skip semantic step: {}", e.getMessage());
            return SemanticInterpretation.failed();
        }
    }

    /** 从模型输出中容错提取 JSON 对象文本。 */
    private JsonNode parseJson(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("Failed to parse small model JSON output: {}", e.getMessage());
            return null;
        }
    }
}