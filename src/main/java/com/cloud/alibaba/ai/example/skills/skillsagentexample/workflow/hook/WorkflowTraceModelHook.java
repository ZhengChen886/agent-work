package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 记录每个模型调用：调用前构造用户视角的输入摘要；
 * 工具调用通过 ToolInterceptor 单独记录（带耗时和结果）。
 */
@HookPositions({HookPosition.BEFORE_MODEL})
public class WorkflowTraceModelHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTraceModelHook.class);

    private final ProcessLogCollector collector;
    private final String fixedSessionId;

    public WorkflowTraceModelHook(ProcessLogCollector collector, String fixedSessionId) {
        this.collector = collector;
        this.fixedSessionId = fixedSessionId;
    }

    @Override
    public String getName() {
        return "WorkflowTraceModelHook";
    }

    private String resolveSessionId(RunnableConfig config) {
        if (fixedSessionId != null) return fixedSessionId;
        return config == null ? null : config.threadId().orElse(null);
    }

    private String resolveAgentName() {
        ReactAgent agent = getAgent();
        return agent != null ? agent.name() : "unknown-agent";
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        String sid = resolveSessionId(config);
        if (sid != null) {
            String preview = previewLastUser(messages);
            collector.append(sid, ProcessLogEntry.modelCall(resolveAgentName(), preview));
            log.info("[trace] model call: session={}, agent={}", sid, resolveAgentName());
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
