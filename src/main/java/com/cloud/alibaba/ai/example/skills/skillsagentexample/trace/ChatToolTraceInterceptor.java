package com.cloud.alibaba.ai.example.skills.skillsagentexample.trace;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 普通聊天用工具调用追踪：记录工具名、参数、结果摘要、耗时与状态。
 * <p>
 * 会话 ID 取自 {@link ChatSessionContext}（ThreadLocal），因为工具拦截器无法直接
 * 拿到 {@code RunnableConfig}；Agent 名通过构造传入的 {@link Supplier} 动态解析。
 */
public class ChatToolTraceInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChatToolTraceInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessLogCollector collector;
    private final Supplier<String> agentNameSupplier;

    public ChatToolTraceInterceptor(ProcessLogCollector collector, Supplier<String> agentNameSupplier) {
        this.collector = collector;
        this.agentNameSupplier = agentNameSupplier;
    }

    @Override
    public String getName() {
        return "ChatToolTraceInterceptor";
    }

    private String resolveAgentName() {
        String name = agentNameSupplier == null ? null : agentNameSupplier.get();
        return name != null ? name : "skill-agent";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        long t0 = System.currentTimeMillis();
        ToolCallResponse response;
        String status = "SUCCESS";
        try {
            response = handler.call(request);
            if (response == null) {
                response = ToolCallResponse.error(request.getToolCallId(), request.getToolName(), "null response");
            }
            if (response.isError()) {
                status = "FAILED";
            }
        } catch (Exception e) {
            status = "FAILED";
            log.warn("[trace] tool call exception: tool={}, err={}", request.getToolName(), e.toString());
            response = ToolCallResponse.error(request.getToolCallId(), request.getToolName(), e);
        }
        long dur = System.currentTimeMillis() - t0;
        String sid = ChatSessionContext.get();
        if (sid != null) {
            Map<String, Object> args = parseArgs(request.getArguments());
            collector.append(sid, ProcessLogEntry.toolCall(
                    resolveAgentName(),
                    request.getToolName(),
                    args,
                    response.getResult(),
                    dur,
                    status));
        }
        return response;
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }
}