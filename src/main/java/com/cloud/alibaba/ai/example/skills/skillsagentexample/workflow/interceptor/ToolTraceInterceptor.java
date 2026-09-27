package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记录每个工具调用：工具名、参数、结果摘要、耗时、状态。
 *
 * <p>由 {@code WorkflowTraceAgentHook.getToolInterceptors()} 注入到每个 sub-Agent。
 * Agent 名称通过共享的 {@link AtomicReference} 从 {@code WorkflowTraceAgentHook} 写入。</p>
 */
public class ToolTraceInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolTraceInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessLogCollector collector;
    private final String fixedSessionId;
    private final AtomicReference<String> currentAgentName;

    public ToolTraceInterceptor(ProcessLogCollector collector, String fixedSessionId,
                                 AtomicReference<String> currentAgentName) {
        this.collector = collector;
        this.fixedSessionId = fixedSessionId;
        this.currentAgentName = currentAgentName;
    }

    @Override
    public String getName() {
        return "ToolTraceInterceptor";
    }

    private String resolveAgentName() {
        String name = currentAgentName == null ? null : currentAgentName.get();
        return name != null ? name : "unknown-agent";
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
        if (fixedSessionId != null) {
            Map<String, Object> args = parseArgs(request.getArguments());
            collector.append(fixedSessionId, ProcessLogEntry.toolCall(
                    resolveAgentName(),
                    request.getToolName(),
                    args,
                    response.getResult(),
                    dur,
                    status));
            log.info("[trace] tool call: session={}, agent={}, tool={}, {}ms, {}",
                    fixedSessionId, resolveAgentName(), request.getToolName(), dur, status);
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
