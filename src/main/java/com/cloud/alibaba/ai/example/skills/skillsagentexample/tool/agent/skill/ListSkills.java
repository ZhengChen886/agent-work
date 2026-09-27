package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.agent.skill;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.SkillsAgent;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;

/**
 * 列出所有可用的 Skill 工具。
 */
@Tool(name = "agent.skill.list", description = "列出所有可用的 Skills", category = "agent.skill")
public class ListSkills extends BaseTool {

    private final SkillsAgent skillsAgent;

    public ListSkills(@Lazy SkillsAgent skillsAgent) {
        this.skillsAgent = skillsAgent;
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) {
        List<SkillMetadata> metadataList = skillsAgent.listSkills();
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (SkillMetadata meta : metadataList) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", meta.getName());
            item.put("description", meta.getDescription() == null ? "" : meta.getDescription());
            items.add(item);
        }
        return ToolResult.ok(Map.of("count", items.size(), "skills", items), 0);
    }
}