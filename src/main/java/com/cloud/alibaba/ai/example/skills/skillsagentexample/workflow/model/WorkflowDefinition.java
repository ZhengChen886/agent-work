package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model;

import java.util.List;

/**
 * 工作流的声明式定义（对应 workflows/&lt;name&gt;.json），由前端画布生成、后端执行。
 *
 * <p>节点类型：</p>
 * <ul>
 *   <li>{@code agent}：引用一个 Agent（{@code ref} 为 agent 名）</li>
 *   <li>{@code skill}：引用某个 Agent 下的单个 skill（{@code agent} + {@code ref} 为 skill 名）</li>
 * </ul>
 */
public record WorkflowDefinition(
        String name,
        String description,
        List<Node> nodes,
        List<Edge> edges) {

    public record Node(String id, String type, String agent, String ref) {
        public boolean isAgentNode() {
            return "agent".equalsIgnoreCase(type);
        }
    }

    public record Edge(String from, String to) {
    }
}