package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl.HitlManager;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl.HitlProperties;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl.HitlToolInterceptor;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.trace.ProcessLogCollector;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.AgentFactory;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.AgentRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.WorkflowEngine;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.WorkflowRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.hook.WorkflowTraceAgentHook;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.hook.WorkflowTraceModelHook;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.AgentDefinition;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 多 Agent 工作流的编排服务：Agent/工作流的列表、保存、热加载与运行。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final AgentRepository agentRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine workflowEngine;
    private final AgentFactory agentFactory;
    private final ProcessLogCollector processLogCollector;
    private final HitlManager hitlManager;
    private final HitlProperties hitlProperties;
    private final Map<String, Agent> workflowCache = new ConcurrentHashMap<>();

    public WorkflowService(AgentRepository agentRepository,
            WorkflowRepository workflowRepository,
            WorkflowEngine workflowEngine,
            AgentFactory agentFactory,
            ProcessLogCollector processLogCollector,
            HitlManager hitlManager,
            HitlProperties hitlProperties) {
        this.agentRepository = agentRepository;
        this.workflowRepository = workflowRepository;
        this.workflowEngine = workflowEngine;
        this.agentFactory = agentFactory;
        this.processLogCollector = processLogCollector;
        this.hitlManager = hitlManager;
        this.hitlProperties = hitlProperties;
    }

    public List<AgentSummary> listAgents() {
        return agentRepository.loadAgents().values().stream()
                .map(this::toSummary)
                .toList();
    }

    public List<WorkflowDefinition> listWorkflows() {
        return workflowRepository.loadAll();
    }

    public Optional<WorkflowDefinition> getWorkflow(String name) {
        return workflowRepository.find(name);
    }

    public void saveWorkflow(WorkflowDefinition wf) {
        validate(wf);
        workflowRepository.save(wf);
        workflowCache.remove(wf.name());
        log.info("Workflow saved and cache invalidated: {}", wf.name());
    }

    public void deleteWorkflow(String name) {
        workflowRepository.delete(name);
        workflowCache.remove(name);
    }

    public synchronized void reload() {
        agentFactory.clear();
        workflowCache.clear();
        log.info("Workflow engine reloaded");
    }

    public Flux<Message> runStream(String name, String userText) {
        return runStream(name, userText, null);
    }

    /**
     * 执行工作流并返回 ReAct 各步骤产生的事件流。
     *
     * @param name       工作流名
     * @param userText   用户输入文本
     * @param sessionId  可选：会话 ID。会作为 threadId 传给所有 sub-Agent，
     *                   触发 Hook/Interceptor 把过程日志写到该会话的过程收集器。
     */
    public Flux<Message> runStream(String name, String userText, String sessionId) {
        try {
            Agent agent = (sessionId == null || sessionId.isBlank())
                    ? getWorkflowAgent(name)
                    : buildForSession(name, sessionId);
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId == null || sessionId.isBlank() ? "_default_" : sessionId)
                    .build();
            return agent.streamMessages(List.of(new UserMessage(userText)), config);
        } catch (GraphRunnerException e) {
            throw new IllegalStateException("工作流执行失败: " + name, e);
        }
    }

    public String runSync(String name, String userText) {
        try {
            List<String> parts = getWorkflowAgent(name).streamMessages(List.of(new UserMessage(userText)))
                    .map(m -> m.getText() == null ? "" : m.getText())
                    .filter(s -> !s.isEmpty())
                    .collectList()
                    .block();
            return parts == null ? "" : String.join("", parts);
        } catch (GraphRunnerException e) {
            throw new IllegalStateException("工作流执行失败: " + name, e);
        }
    }

    private Agent getWorkflowAgent(String name) {
        return workflowCache.computeIfAbsent(name, n -> {
            WorkflowDefinition wf = workflowRepository.find(n)
                    .orElseThrow(() -> new NoSuchElementException("工作流不存在: " + n));
            return workflowEngine.build(wf);
        });
    }

    /**
     * 构建一个绑定到特定 sessionId 的工作流 Agent：
     * 每个 sub-Agent 都会注入 trace Hook + Tool Interceptor，
     * 它们把过程事件写到 ProcessLogCollector.sessionId 桶里。
     *
     * <p>每次带 sessionId 的运行都重新构建，确保缓存中无跨会话污染。
     * Hook 是 per-Agent 重新创建的，避免跨 agent 共享同一实例导致 agent 名错乱。</p>
     */
    private Agent buildForSession(String name, String sessionId) {
        WorkflowDefinition wf = workflowRepository.find(name)
                .orElseThrow(() -> new NoSuchElementException("工作流不存在: " + name));

        java.util.function.Supplier<List<Hook>> traceHooksFactory =
                () -> {
                    // HITL 人工审批：工作流路径使用固定 sessionId（与 trace 拦截器一致）
                    HitlToolInterceptor hitlToolInterceptor = new HitlToolInterceptor(
                            hitlManager, hitlProperties, () -> sessionId);
                    WorkflowTraceAgentHook ah =
                            new WorkflowTraceAgentHook(processLogCollector, sessionId, hitlToolInterceptor);
                    WorkflowTraceModelHook mh =
                            new WorkflowTraceModelHook(processLogCollector, sessionId);
                    return List.of(ah, mh);
                };
        return workflowEngine.buildWithHookFactory(wf, traceHooksFactory);
    }

    private void validate(WorkflowDefinition wf) {
        if (wf.name() == null || wf.name().isBlank()) {
            throw new IllegalArgumentException("工作流名称不能为空");
        }
        if (wf.nodes() == null || wf.nodes().isEmpty()) {
            throw new IllegalArgumentException("工作流至少需要一个节点");
        }
        Map<String, AgentDefinition> agents = agentRepository.loadAgents();
        for (WorkflowDefinition.Node node : wf.nodes()) {
            String agentName = node.isAgentNode() ? node.ref() : node.agent();
            if (agentName == null || !agents.containsKey(agentName)) {
                throw new IllegalArgumentException("节点引用了不存在的 agent: " + agentName);
            }
        }
    }

    private AgentSummary toSummary(AgentDefinition def) {
        List<String> skillNames = agentFactory.listSkills(def).stream()
                .map(s -> s.getName())
                .toList();
        return new AgentSummary(def.name(), def.description(), skillNames,
                def.toolsOrDefault(), def.skillFlow());
    }

    public record AgentSummary(String name, String description, List<String> skills,
            List<String> tools, List<String> skillFlow) {
    }
}