package com.cloud.alibaba.ai.example.skills.skillsagentexample.trace;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 普通聊天用模型调用追踪：每次模型调用前记录一条 MODEL_CALL 条目，
 * 展示当前阶段在「思考下一步 / 回应用户」。会话 ID 从 {@link ChatSessionContext} 解析，
 * 若能从 {@link RunnableConfig#threadId()} 得到则优先使用。
 */
@HookPositions({HookPosition.BEFORE_MODEL})
public class ChatTraceModelHook extends MessagesModelHook {

    private final ProcessLogCollector collector;

    public ChatTraceModelHook(ProcessLogCollector collector) {
        this.collector = collector;
    }

    @Override
    public String getName() {
        return "ChatTraceModelHook";
    }

    private String resolveSessionId(RunnableConfig config) {
        if (config != null) {
            String id = config.threadId().orElse(null);
            if (id != null && !id.isBlank() && !"_default_".equals(id)) {
                return id;
            }
        }
        return ChatSessionContext.get();
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        String sid = resolveSessionId(config);
        if (sid != null) {
            String agentName = getAgentName();
            collector.append(sid, ProcessLogEntry.modelCall(
                    agentName != null ? agentName : "skill-agent", previewLastUser(messages)));
        }
        return new AgentCommand(messages);
    }

    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        return new AgentCommand(messages);
    }

    private static String previewLastUser(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
                if (responses != null && !responses.isEmpty()) {
                    return "工具返回 → 思考下一步（" + responses.get(0).name() + "）";
                }
            }
            String text = m.getText();
            if (text != null && !text.isBlank()) {
                return text.length() > 160 ? text.substring(0, 160) + "…" : text;
            }
        }
        return null;
    }
}