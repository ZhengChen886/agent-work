package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.AgentDefinition;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.WorkflowDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 把 {@link WorkflowDefinition} 编译成可执行的顶层 {@link Agent}。
 *
 * <p>首期支持两种拓扑：顺序链（SequentialAgent）与纯并行（ParallelAgent）；
 * 分叉/汇合等复杂 DAG 暂不支持，编译时抛异常。</p>
 */
@Component
public class WorkflowEngine {

    private final AgentFactory agentFactory;
    private final AgentRepository agentRepository;

    public WorkflowEngine(AgentFactory agentFactory, AgentRepository agentRepository) {
        this.agentFactory = agentFactory;
        this.agentRepository = agentRepository;
    }

    public Agent build(WorkflowDefinition wf) {
        return build(wf, List.of(), List.of());
    }

    public Agent build(WorkflowDefinition wf, List<Hook> extraHooks, List<Interceptor> extraInterceptors) {
        return buildWithHookFactory(wf, () -> extraHooks);
    }

    /**
     * 使用 Hook 工厂构建工作流 Agent：每个 sub-Agent 在构建时由工厂调用一次，
     * 拿到独立的 Hook 实例，避免跨 sub-Agent 共享同一 Hook 引用。
     */
    public Agent buildWithHookFactory(WorkflowDefinition wf,
                                      java.util.function.Supplier<List<Hook>> perAgentHooksSupplier) {
        Map<String, AgentDefinition> agents = agentRepository.loadAgents();
        Map<String, Agent> byId = new LinkedHashMap<>();
        for (WorkflowDefinition.Node node : wf.nodes()) {
            AgentDefinition def = resolve(agents, node);
            byId.put(node.id(), agentFactory.buildNode(def, node, List.of(), List.of(), perAgentHooksSupplier));
        }
        if (byId.isEmpty()) {
            throw new IllegalArgumentException("工作流没有节点: " + wf.name());
        }

        List<WorkflowDefinition.Edge> edges = wf.edges() == null ? List.of() : wf.edges();
        if (edges.isEmpty()) {
            List<Agent> subs = wf.nodes().stream().map(n -> byId.get(n.id())).toList();
            if (subs.size() == 1) {
                return subs.get(0);
            }
            return ParallelAgent.builder()
                    .name(wf.name())
                    .description(wf.description())
                    .subAgents(subs)
                    .build();
        }

        Order order = topoSort(wf.nodes(), edges);
        if (!order.linear()) {
            throw new IllegalArgumentException("工作流暂不支持分叉/汇合拓扑: " + wf.name());
        }
        List<Agent> subs = order.sortedIds().stream().map(byId::get).toList();
        if (subs.size() == 1) {
            return subs.get(0);
        }
        return SequentialAgent.builder()
                .name(wf.name())
                .description(wf.description())
                .subAgents(subs)
                .build();
    }

    private AgentDefinition resolve(Map<String, AgentDefinition> agents, WorkflowDefinition.Node node) {
        String agentName = node.isAgentNode() ? node.ref() : node.agent();
        AgentDefinition def = agents.get(agentName);
        if (def == null) {
            throw new IllegalArgumentException("未找到 agent: " + agentName);
        }
        return def;
    }

    private Order topoSort(List<WorkflowDefinition.Node> nodes, List<WorkflowDefinition.Edge> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowDefinition.Node n : nodes) {
            indegree.put(n.id(), 0);
            adjacency.put(n.id(), new ArrayList<>());
        }
        for (WorkflowDefinition.Edge e : edges) {
            if (!indegree.containsKey(e.from()) || !indegree.containsKey(e.to())) {
                throw new IllegalArgumentException("边引用了不存在的节点: " + e.from() + " -> " + e.to());
            }
            adjacency.get(e.from()).add(e.to());
            indegree.put(e.to(), indegree.get(e.to()) + 1);
        }

        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        boolean linear = true;
        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            if (queue.size() > 1) {
                linear = false;
            }
            String id = queue.poll();
            if (!visited.add(id)) {
                continue;
            }
            sorted.add(id);
            for (String next : adjacency.get(id)) {
                indegree.put(next, indegree.get(next) - 1);
                if (indegree.get(next) == 0) {
                    queue.add(next);
                }
            }
        }
        if (sorted.size() != nodes.size()) {
            throw new IllegalArgumentException("工作流存在环，无法执行");
        }
        return new Order(sorted, linear);
    }

    private record Order(List<String> sortedIds, boolean linear) {
    }
}