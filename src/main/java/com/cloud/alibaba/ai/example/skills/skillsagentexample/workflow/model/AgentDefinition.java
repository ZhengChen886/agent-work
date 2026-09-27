package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model;

import java.util.List;

/**
 * 单个 Agent 的声明式定义（对应 agents/&lt;name&gt;/agent.json）。
 *
 * @param name        Agent 唯一名（等于目录名）
 * @param description Agent 能力描述
 * @param systemPrompt 该 Agent 的系统提示词
 * @param tools       该 Agent 可用的工具白名单（缺省=全部基础工具）
 * @param skillFlow   该 Agent 内部的 skill 顺序子流程（缺省=由 LLM 自主选择）
 */
public record AgentDefinition(
        String name,
        String description,
        String systemPrompt,
        List<String> tools,
        List<String> skillFlow) {

    public List<String> toolsOrDefault() {
        return tools == null ? List.of() : tools;
    }
}