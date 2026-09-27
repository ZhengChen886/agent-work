package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.skills.SkillsInterceptor;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.AgentDefinition;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.WorkflowDefinition;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 根据 {@link AgentDefinition} 构建可执行的 {@link Agent}。
 *
 * <p>每个 Agent 拥有独立的 skills 目录（agents/&lt;name&gt;/skills），
 * 支持把单个 skill 或 skill 顺序子流程构建成可编排的执行节点。</p>
 */
@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final AgentRepository agentRepository;
    private final Map<String, FileSystemSkillRegistry> registries = new ConcurrentHashMap<>();

    public AgentFactory(ChatModel chatModel, ToolRegistry toolRegistry, AgentRepository agentRepository) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.agentRepository = agentRepository;
    }

    public synchronized void clear() {
        registries.clear();
    }

    public List<SkillMetadata> listSkills(AgentDefinition def) {
        return registryFor(def).listAll();
    }

    /** 构建一个 Agent 节点（含其内部 skill 子流程）。 */
    public Agent buildAgent(AgentDefinition def) {
        return buildAgent(def, List.of(), List.of(), null);
    }

    public Agent buildAgent(AgentDefinition def, List<Hook> extraHooks, List<Interceptor> extraInterceptors) {
        return buildAgent(def, extraHooks, extraInterceptors, null);
    }

    public Agent buildAgent(AgentDefinition def,
                           List<Hook> extraHooks,
                           List<Interceptor> extraInterceptors,
                           java.util.function.Supplier<List<Hook>> perAgentHooksSupplier) {
        List<String> flow = def.skillFlow();
        if (flow == null || flow.isEmpty()) {
            return buildReactAgent(def.name(), prompt(def), registryFor(def), def,
                    extraHooks, extraInterceptors, perAgentHooksSupplier);
        }
        List<Agent> subs = flow.stream()
                .map(skillName -> buildSkillAgent(def, skillName, extraHooks, extraInterceptors, perAgentHooksSupplier))
                .toList();
        if (subs.size() == 1) {
            return subs.get(0);
        }
        return SequentialAgent.builder()
                .name(def.name())
                .description(def.description())
                .subAgents(subs)
                .build();
    }

    /** 构建一个单 skill 执行节点。 */
    public Agent buildSkillAgent(AgentDefinition def, String skillName) {
        return buildSkillAgent(def, skillName, List.of(), List.of(), null);
    }

    public Agent buildSkillAgent(AgentDefinition def, String skillName,
                                 List<Hook> extraHooks, List<Interceptor> extraInterceptors) {
        return buildSkillAgent(def, skillName, extraHooks, extraInterceptors, null);
    }

    public Agent buildSkillAgent(AgentDefinition def, String skillName,
                                 List<Hook> extraHooks, List<Interceptor> extraInterceptors,
                                 java.util.function.Supplier<List<Hook>> perAgentHooksSupplier) {
        SkillRegistry registry = new FilteredSkillRegistry(registryFor(def), Set.of(skillName));
        String name = def.name() + "::" + skillName;
        return buildReactAgent(name,
                "你是技能执行节点，请阅读并执行技能「" + skillName + "」，完成任务并输出结果。",
                registry, def, extraHooks, extraInterceptors, perAgentHooksSupplier);
    }

    /** 按工作流节点类型分发构建。 */
    public Agent buildNode(AgentDefinition def, WorkflowDefinition.Node node) {
        return buildNode(def, node, List.of(), List.of(), null);
    }

    public Agent buildNode(AgentDefinition def, WorkflowDefinition.Node node,
                           List<Hook> extraHooks, List<Interceptor> extraInterceptors) {
        return buildNode(def, node, extraHooks, extraInterceptors, null);
    }

    public Agent buildNode(AgentDefinition def, WorkflowDefinition.Node node,
                           List<Hook> extraHooks, List<Interceptor> extraInterceptors,
                           java.util.function.Supplier<List<Hook>> perAgentHooksSupplier) {
        if (node.isAgentNode()) {
            return buildAgent(def, extraHooks, extraInterceptors, perAgentHooksSupplier);
        }
        return buildSkillAgent(def, node.ref(), extraHooks, extraInterceptors, perAgentHooksSupplier);
    }

    private ReactAgent buildReactAgent(String name, String systemPrompt, SkillRegistry registry,
            AgentDefinition def, List<Hook> extraHooks, List<Interceptor> extraInterceptors) {
        return buildReactAgent(name, systemPrompt, registry, def, extraHooks, extraInterceptors, null);
    }

    private ReactAgent buildReactAgent(String name, String systemPrompt, SkillRegistry registry,
            AgentDefinition def, List<Hook> extraHooks, List<Interceptor> extraInterceptors,
            java.util.function.Supplier<List<Hook>> perAgentHooksSupplier) {
        List<Interceptor> interceptors = new ArrayList<>();
        interceptors.add(SkillsInterceptor.builder().skillRegistry(registry).build());
        if (extraInterceptors != null) interceptors.addAll(extraInterceptors);

        List<Hook> hooks = new ArrayList<>();
        hooks.add(ShellToolAgentHook.builder().shellToolName("shell").build());
        // perAgentHooksSupplier 每次调用都返回全新 hooks 实例，避免跨 agent 共享同一实例
        if (perAgentHooksSupplier != null) {
            List<Hook> perAgent = perAgentHooksSupplier.get();
            if (perAgent != null) hooks.addAll(perAgent);
        } else if (extraHooks != null) {
            hooks.addAll(extraHooks);
        }

        return ReactAgent.builder()
                .name(name)
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .hooks(hooks)
                .interceptors(interceptors)
                .tools(toolsFor(def))
                .enableLogging(true)
                .build();
    }

    private List<ToolCallback> toolsFor(AgentDefinition def) {
        List<String> whitelist = def.toolsOrDefault();
        if (whitelist.isEmpty()) {
            return toolRegistry.toToolCallbacks();
        }
        return toolRegistry.toToolCallbacks(whitelist);
    }

    private FileSystemSkillRegistry registryFor(AgentDefinition def) {
        return registries.computeIfAbsent(def.name(), n -> {
            Path skillsDir = agentRepository.skillsDirOf(n);
            try {
                Files.createDirectories(skillsDir);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create skills dir: " + skillsDir, e);
            }
            log.info("Building skill registry for agent '{}': {}", n, skillsDir);
            return FileSystemSkillRegistry.builder()
                    .userSkillsDirectory(skillsDir.toString())
                    .projectSkillsDirectory(skillsDir.toString())
                    .autoLoad(true)
                    .build();
        });
    }

    private String prompt(AgentDefinition def) {
        if (def.systemPrompt() != null && !def.systemPrompt().isBlank()) {
            return def.systemPrompt();
        }
        return "你是一个专业的工作流执行节点，请完成你的任务并输出结果。";
    }
}