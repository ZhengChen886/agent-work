package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.SkillInfo;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.AgentService;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.AgentDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Agent 管理 REST API。
 *
 * <ul>
 *   <li>GET    /api/agents                          — 列表</li>
 *   <li>GET    /api/agents/{name}                   — 详情</li>
 *   <li>POST   /api/agents                          — 新建（name 不可变）</li>
 *   <li>POST   /api/agents/{name}                   — 保存/更新（兼容工作流面板旧入口）</li>
 *   <li>PUT    /api/agents/{name}                   — 更新</li>
 *   <li>DELETE /api/agents/{name}                   — 删除</li>
 *   <li>POST   /api/agents/reload                   — 热加载</li>
 *   <li>GET    /api/agents/{name}/skills            — 该 Agent 的 skills 列表</li>
 *   <li>POST   /api/agents/{name}/skills            — 上传 skill（zip）</li>
 *   <li>DELETE /api/agents/{name}/skills/{skill}    — 删除 skill</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public List<AgentService.AgentDetail> list() {
        return agentService.listAgents();
    }

    @GetMapping("/{name}")
    public AgentService.AgentDetail get(@PathVariable String name) {
        return agentService.getAgent(name);
    }

    @PostMapping
    public AgentService.AgentDetail create(@RequestBody AgentDefinition def) {
        return agentService.createAgent(def);
    }

    /** 向后兼容：工作流画布面板沿用 POST /api/agents/{name} 保存 Agent。 */
    @PostMapping("/{name}")
    public AgentService.AgentDetail save(@PathVariable String name, @RequestBody AgentDefinition def) {
        return agentService.updateAgent(name, def);
    }

    @PutMapping("/{name}")
    public AgentService.AgentDetail update(@PathVariable String name, @RequestBody AgentDefinition def) {
        return agentService.updateAgent(name, def);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        agentService.deleteAgent(name);
        return Map.of("ok", true);
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        agentService.reload();
        return Map.of("ok", true);
    }

    @GetMapping("/{name}/skills")
    public List<SkillInfo> skills(@PathVariable String name) {
        return agentService.listAgentSkills(name);
    }

    @PostMapping("/{name}/skills")
    public SkillInfo uploadSkill(@PathVariable String name, @RequestParam("file") MultipartFile file) {
        return agentService.uploadAgentSkill(name, file);
    }

    @DeleteMapping("/{name}/skills/{skillName}")
    public Map<String, Object> deleteSkill(@PathVariable String name, @PathVariable String skillName) {
        agentService.deleteAgentSkill(name, skillName);
        return Map.of("ok", true);
    }
}