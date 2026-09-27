package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.AgentDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 扫描/读写 agents 目录，每个 agents/&lt;name&gt;/agent.json 对应一个 {@link AgentDefinition}。
 *
 * <p>目录支持通过 {@code workflow.agents-dir} 配置，缺省为 {@code agents}。</p>
 */
@Component
public class AgentRepository {

    private static final Logger log = LoggerFactory.getLogger(AgentRepository.class);

    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentRepository(WorkflowProperties properties) {
        this.properties = properties;
    }

    /** 解析 agents 根目录：优先项目根，回退当前工作目录。 */
    private Path resolveRoot() {
        Path projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path dir = projectDir.resolve(properties.getAgentsDir());
        if (!Files.isDirectory(dir)) {
            dir = Path.of(properties.getAgentsDir()).toAbsolutePath();
        }
        return dir;
    }

    /** 某个 Agent 的目录：agents/&lt;name&gt;。 */
    public Path dirOf(String name) {
        return resolveRoot().resolve(name).normalize();
    }

    /** 某个 Agent 的专属 skills 目录：agents/&lt;name&gt;/skills。 */
    public Path skillsDirOf(String name) {
        return dirOf(name).resolve("skills").normalize();
    }

    public Map<String, AgentDefinition> loadAgents() {
        Map<String, AgentDefinition> result = new LinkedHashMap<>();
        Path dir = resolveRoot();
        if (!Files.isDirectory(dir)) {
            log.warn("Agents directory not found: {}", dir);
            return result;
        }

        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.filter(Files::isDirectory).sorted().toList()) {
                Path agentJson = p.resolve("agent.json");
                if (!Files.isRegularFile(agentJson)) {
                    continue;
                }
                try {
                    AgentDefinition def = objectMapper.readValue(agentJson.toFile(), AgentDefinition.class);
                    if (def.name() == null || def.name().isBlank()) {
                        log.warn("Skip agent without name: {}", agentJson);
                        continue;
                    }
                    result.put(def.name(), def);
                } catch (IOException e) {
                    log.error("Failed to parse agent definition: {}", agentJson, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to list agents directory: {}", dir, e);
        }
        return result;
    }

    public Optional<AgentDefinition> find(String name) {
        Path agentJson = dirOf(name).resolve("agent.json");
        if (!Files.isRegularFile(agentJson)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(agentJson.toFile(), AgentDefinition.class));
        } catch (IOException e) {
            log.error("Failed to parse agent definition: {}", agentJson, e);
            return Optional.empty();
        }
    }

    /** 保存 Agent 定义（覆盖写 agent.json，并确保 skills 子目录存在）。 */
    public void save(AgentDefinition def) {
        validateName(def.name());
        try {
            Path dir = dirOf(def.name());
            Files.createDirectories(dir.resolve("skills"));
            Path agentJson = dir.resolve("agent.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(agentJson.toFile(), def);
            log.info("Agent saved: {}", agentJson);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save agent: " + def.name(), e);
        }
    }

    /** 删除 Agent 整个目录（含其专属 skills）。 */
    public void delete(String name) {
        validateName(name);
        Path dir = dirOf(name);
        if (!Files.exists(dir)) {
            return;
        }
        if (!dir.startsWith(resolveRoot())) {
            throw new IllegalArgumentException("非法的 Agent 路径: " + name);
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
            log.info("Agent deleted: {}", dir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete agent: " + name, e);
        }
    }

    public boolean exists(String name) {
        return Files.isDirectory(dirOf(name));
    }

    /** 校验 Agent 名称：非空、不含路径分隔符、非 "." / ".."。 */
    public void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent 名称不能为空");
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("非法的 Agent 名称: " + name);
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("非法的 Agent 名称（不得包含路径分隔符）: " + name);
        }
    }
}