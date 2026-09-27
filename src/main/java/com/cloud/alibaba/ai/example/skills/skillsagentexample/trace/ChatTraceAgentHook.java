package com.cloud.alibaba.ai.example.skills.skillsagentexample.trace;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 普通聊天用的 {@link AgentHook}；目的与 workflow 版一致：记录 skill-agent 的生命周期
 * 并挂载工具调用追踪拦截器。
 * <p>
 * 与 workflow 版的区别在于会话 ID 来源：此处不再绑定固定 sessionId，
 * 而是优先从 {@link RunnableConfig#threadId()}、其次从 {@link ChatSessionContext}
 * （ThreadLocal）动态解析，从而适配单例共享的 {@code SkillsAgent} 在多会话并发下的追踪路由。
 */
public class ChatTraceAgentHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(ChatTraceAgentHook.class);

    private final ProcessLogCollector collector;
    private final ChatToolTraceInterceptor toolInterceptor;
    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();

    public ChatTraceAgentHook(ProcessLogCollector collector) {
        this.collector = collector;
        this.toolInterceptor = new ChatToolTraceInterceptor(collector, this::resolveAgentName);
    }

    @Override
    public String getName() {
        return "ChatTraceAgentHook";
    }

    @Override
    public List<ToolInterceptor> getToolInterceptors() {
        return List.of(toolInterceptor);
    }

    @Override
    public int getOrder() {
        return 0;
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

    private String resolveAgentName() {
        String name = getAgentName();
        return name != null ? name : "skill-agent";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String sid = resolveSessionId(config);
        if (sid != null) {
            String agentName = resolveAgentName();
            startTimes.put(sid, System.currentTimeMillis());
            collector.append(sid, ProcessLogEntry.agentStart(agentName));
            log.debug("[trace] agent start: session={}, agent={}", sid, agentName);
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        String sid = resolveSessionId(config);
        if (sid != null) {
            String agentName = resolveAgentName();
            long durationMs = 0;
            Long start = startTimes.remove(sid);
            if (start != null) {
                durationMs = System.currentTimeMillis() - start;
            }
            collector.append(sid, ProcessLogEntry.agentEnd(agentName, durationMs, "SUCCESS"));
        }
        return CompletableFuture.completedFuture(Map.of());
    }
}