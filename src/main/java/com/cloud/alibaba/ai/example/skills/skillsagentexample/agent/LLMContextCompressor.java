package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Message;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.entity.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LLM 的上下文压缩器。
 * 当历史消息总量超过阈值时，调用 LLM 对早期消息生成精炼摘要，
 * 保留最近 keepRecentRounds 轮的原文，并返回 summary + recent。
 * <p>
 * 摘要提示词从 {@link PromptTemplateManager} 加载（模板名：context-summary），
 * 模板中使用 {@code %s} 占位符插入历史对话内容。
 * 压缩失败时降级为仅保留最近 N 轮原文，compressed=false。
 */
@Component
public class LLMContextCompressor implements ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(LLMContextCompressor.class);

    /** 模板加载失败时的降级摘要提示词 */
    private static final String FALLBACK_SUMMARY_PROMPT = """
            你是对话摘要生成器。请对以下历史对话生成一段精炼摘要，保留：
            - 关键事实、数字、术语
            - 用户明确的偏好和约束
            - 已完成的事项 / 未解决的问题
            - 重要结论和决定
            用中文、结构化要点输出，不超过 500 字。
            
            === 历史对话 ===
            %s
            === 历史对话结束 ===
            """;

    private final ChatModel chatModel;
    private final PromptTemplateManager promptTemplateManager;
    private final ContextProperties contextProperties;

    public LLMContextCompressor(ChatModel chatModel,
                                PromptTemplateManager promptTemplateManager,
                                ContextProperties contextProperties) {
        this.chatModel = chatModel;
        this.promptTemplateManager = promptTemplateManager;
        this.contextProperties = contextProperties;
    }

    @Override
    public CompressionResult compress(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return new CompressionResult(List.of(), false, null, List.of());
        }

        // 用 TokenEstimator 精确估算历史消息的 token 数
        int estimatedTokens = history.stream()
                .mapToInt(m -> TokenEstimator.estimate(m.getContent()))
                .sum();

        // 动态预算 = 上下文窗口 - 预留输出 - 摘要上限
        int budget = contextProperties.getInputBudgetTokens();

        log.debug("History estimatedTokens={}, inputBudget={}, maxContext={}, reservedOutput={}, summaryMax={}",
                estimatedTokens, budget,
                contextProperties.getMaxContextTokens(),
                contextProperties.getReservedOutputTokens(),
                contextProperties.getSummaryMaxTokens());

        if (estimatedTokens <= budget) {
            // 无需压缩，返回全部原文
            return new CompressionResult(new ArrayList<>(history), false, null, List.of());
        }

        // 需要压缩
        int keepRecentRounds = contextProperties.getKeepRecentRounds();
        int keepCount = keepRecentRounds * 2;
        List<Message> recentMessages;
        List<Message> earlierMessages;

        if (history.size() <= keepCount) {
            // 消息条数本身不多，但单条很长，也走摘要策略
            recentMessages = List.of();
            earlierMessages = history;
        } else {
            recentMessages = new ArrayList<>(history.subList(history.size() - keepCount, history.size()));
            earlierMessages = new ArrayList<>(history.subList(0, history.size() - keepCount));
        }

        // 调用 LLM 生成摘要
        String summary = callSummaryLLM(earlierMessages);

        if (summary == null) {
            // 降级：仅保留最近 N 轮原文
            log.warn("Summary LLM call failed, falling back to truncate mode");
            return new CompressionResult(recentMessages.isEmpty() ? new ArrayList<>(history) : recentMessages,
                    false, null, List.of());
        }

        // 构造 summary 消息（role=summary），插入到保留的最近消息之前
        List<Message> result = new ArrayList<>();
        if (!summary.isBlank()) {
            // summary 消息不绑定 conversation（等 ChatController 持久化时再绑定）
            Message summaryMsg = new Message();
            summaryMsg.setRole(Role.SUMMARY);
            summaryMsg.setContent(summary);
            result.add(summaryMsg);
        }
        result.addAll(recentMessages);

        return new CompressionResult(
                result,
                true,
                summary,
                earlierMessages  // 这些是需要持久化层删除的
        );
    }

    /**
     * 估算 token 数：使用 {@link TokenEstimator} 的启发式算法（区分 CJK 与 ASCII）。
     */
    public int estimateTokens(String text) {
        return TokenEstimator.estimate(text);
    }

    private String callSummaryLLM(List<Message> earlierMessages) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Message m : earlierMessages) {
                sb.append("[").append(m.getRole().name().toLowerCase()).append("]: ")
                        .append(m.getContent() == null ? "" : m.getContent())
                        .append("\n\n");
            }

            String promptTemplate = promptTemplateManager.getContentOrDefault(
                    "context-summary", FALLBACK_SUMMARY_PROMPT);
            String prompt = promptTemplate.formatted(sb);

            List<org.springframework.ai.chat.messages.Message> msgList = List.of(
                    new SystemMessage("你是对话摘要助手，擅长提炼要点。"),
                    new UserMessage(prompt)
            );

            AssistantMessage reply = (AssistantMessage) chatModel.call(new Prompt(msgList)).getResult().getOutput();
            String content = reply.getText();
            log.info("Summary generated, length={}", content == null ? 0 : content.length());
            return content;
        } catch (Exception e) {
            log.error("Failed to call LLM for summary generation", e);
            return null;
        }
    }
}
