package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl.HitlToolInterceptor;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogEntry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.interceptor.ToolTraceInterceptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把每个 sub-Agent 的生命周期（beforeAgent / afterAgent）记录到
 * {@link ProcessLogCollector}，并通过 {@link #getToolInterceptors()} 把
 * 工具调用追踪拦截器挂上去，从而在一个 Hook 上同时记录 AGENT 与 TOOL 事件。
 *
 * <p>继承 {@link AgentHook}（而非 MessagesAgentHook），因为框架只调度 AgentHook 的
 * beforeAgent(OverAllState, RunnableConfig) 方法。</p>
 */
public class WorkflowTraceAgentHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTraceAgentHook.class);

    private final ProcessLogCollector collector;
    private final String fixedSessionId;
    private final AtomicReference<String> currentAgentName = new AtomicReference<>();
    private final ToolTraceInterceptor toolInterceptor;
    /** HITL 人工审批拦截器，可为 null（未装配时跳过）。 */
    private final HitlToolInterceptor hitlInterceptor;
    /** sessionId -> agentName -> start timestamp：用于计算 duration。 */
    private final Map<String, Map<String, Long>> startTimes = new ConcurrentHashMap<>();

    public WorkflowTraceAgentHook(ProcessLogCollector collector, String fixedSessionId) {
        this(collector, fixedSessionId, null);
    }

    public WorkflowTraceAgentHook(ProcessLogCollector collector, String fixedSessionId,
                                  HitlToolInterceptor hitlInterceptor) {
        this.collector = collector;
        this.fixedSessionId = fixedSessionId;
        this.toolInterceptor = new ToolTraceInterceptor(collector, fixedSessionId, currentAgentName);
        this.hitlInterceptor = hitlInterceptor;
    }

    @Override
    public String getName() {
        return "WorkflowTraceAgentHook";
    }

    private ReactAgent injectedAgent;

    @Override
    public ReactAgent getAgent() {
        return injectedAgent;
    }

    @Override
    public void setAgent(ReactAgent agent) {
        this.injectedAgent = agent;
        log.info("[trace] setAgent called: {}", agent == null ? "null" : agent.name());
        if (agent != null) {
            currentAgentName.set(agent.name());
        }
    }

    @Override
    public List<ToolInterceptor> getToolInterceptors() {
        return hitlInterceptor == null
                ? List.of(toolInterceptor)
                : List.of(toolInterceptor, hitlInterceptor);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private String resolveSessionId(RunnableConfig config) {
        if (fixedSessionId != null) return fixedSessionId;
        return config == null ? null : config.threadId().orElse(null);
    }

    private String resolveAgentName() {
        String name = currentAgentName.get();
        if (name != null) return name;
        ReactAgent agent = getAgent();
        return agent != null ? agent.name() : "unknown-agent";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String sid = resolveSessionId(config);
        String agentName = resolveAgentName();
        if (sid != null) {
            startTimes.computeIfAbsent(sid, k -> new ConcurrentHashMap<>())
                    .put(agentName, System.currentTimeMillis());
            collector.append(sid, ProcessLogEntry.agentStart(agentName));
            log.info("[trace] agent start: session={}, agent={}", sid, agentName);
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        String sid = resolveSessionId(config);
        String agentName = resolveAgentName();
        if (sid != null) {
            Map<String, Long> map = startTimes.get(sid);
            long durationMs = 0;
            if (map != null) {
                Long start = map.remove(agentName);
                if (start != null) durationMs = System.currentTimeMillis() - start;
            }
            collector.append(sid, ProcessLogEntry.agentEnd(agentName, durationMs, "SUCCESS"));
            log.info("[trace] agent end: session={}, agent={}, {}ms", sid, agentName, durationMs);
        }
        return CompletableFuture.completedFuture(Map.of());
    }
}
