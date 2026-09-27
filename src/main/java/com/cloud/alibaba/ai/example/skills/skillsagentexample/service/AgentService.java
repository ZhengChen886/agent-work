package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.SkillInfo;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.AgentRepository;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.AgentDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent 元数据 CRUD + 专属 skills 目录管理。
 *
 * <p>ZIP 安装由 {@link SkillZipInstaller} 统一处理,与 {@link SkillService} 共用实现。</p>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agentRepository;
    private final WorkflowService workflowService;
    private final SkillZipInstaller zipInstaller;

    public AgentService(AgentRepository agentRepository,
                        WorkflowService workflowService,
                        SkillZipInstaller zipInstaller) {
        this.agentRepository = agentRepository;
        this.workflowService = workflowService;
        this.zipInstaller = zipInstaller;
    }

    // ==================== Agent 元数据 CRUD ====================

    public List<AgentDetail> listAgents() {
        return agentRepository.loadAgents().values().stream()
                .map(this::toDetail)
                .toList();
    }

    public AgentDetail getAgent(String name) {
        validateAgentName(name);
        return toDetail(require(name));
    }

    public AgentDetail createAgent(AgentDefinition def) {
        String name = def == null ? null : def.name();
        validateAgentName(name);
        if (agentRepository.exists(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同名 Agent 已存在: " + name);
        }
        agentRepository.save(normalize(name, def));
        reload();
        log.info("Agent created: {}", name);
        return getAgent(name);
    }

    public AgentDetail updateAgent(String name, AgentDefinition def) {
        validateAgentName(name);
        require(name);
        agentRepository.save(normalize(name, def));
        reload();
        log.info("Agent updated: {}", name);
        return getAgent(name);
    }

    public void deleteAgent(String name) {
        validateAgentName(name);
        require(name);
        agentRepository.delete(name);
        reload();
        log.info("Agent deleted: {}", name);
    }

    public void reload() {
        workflowService.reload();
    }

    // ==================== Agent 专属 skills 管理 ====================

    public List<SkillInfo> listAgentSkills(String name) {
        validateAgentName(name);
        require(name);
        Path skillsDir = agentRepository.skillsDirOf(name);
        if (!Files.isDirectory(skillsDir)) return List.of();
        try (Stream<Path> stream = Files.list(skillsDir)) {
            return stream.filter(Files::isDirectory)
                    .map(this::toSkillInfo)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(SkillInfo::name))
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list skills of agent '{}'", name, e);
            return List.of();
        }
    }

    public SkillInfo uploadAgentSkill(String name, MultipartFile file) {
        validateAgentName(name);
        require(name);

        SkillZipInstaller.ParsedZip parsed = zipInstaller.parse(file, "Agent 技能上传");
        ZipEntryDataHolder skillMdData = findSkillMdEntry(parsed);
        String skillName = zipInstaller.readSkillName(skillMdData.data());

        Path skillsRoot = agentRepository.skillsDirOf(name);
        Path target = skillsRoot.resolve(skillName).normalize();
        if (!target.startsWith(skillsRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的技能路径");
        }
        if (Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同名技能已存在: " + skillName);
        }

        try {
            zipInstaller.extractTo(parsed, target);
        } catch (ResponseStatusException ex) {
            zipInstaller.deleteDirectoryQuietly(target);
            throw ex;
        }
        reload();
        log.info("Skill '{}' uploaded to agent '{}'", skillName, name);
        return new SkillInfo(skillName,
                readFrontmatterDescription(skillMdData.data()),
                target.toAbsolutePath().toString(),
                listFiles(target));
    }

    public void deleteAgentSkill(String name, String skillName) {
        validateAgentName(name);
        require(name);
        if (!zipInstaller.isSafeSkillName(skillName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的技能名称: " + skillName);
        }
        Path skillsRoot = agentRepository.skillsDirOf(name);
        Path target = skillsRoot.resolve(skillName).normalize();
        if (!target.startsWith(skillsRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的技能路径");
        }
        if (!Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在: " + skillName);
        }
        try {
            zipInstaller.deleteRecursively(target);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "删除技能失败: " + e.getMessage(), e);
        }
        reload();
        log.info("Skill '{}' deleted from agent '{}'", skillName, name);
    }

    // ==================== 私有辅助 ====================

    private AgentDefinition require(String name) {
        return agentRepository.find(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: " + name));
    }

    private AgentDefinition normalize(String name, AgentDefinition def) {
        return new AgentDefinition(name, def.description(), def.systemPrompt(), def.tools(), def.skillFlow());
    }

    private AgentDetail toDetail(AgentDefinition def) {
        return new AgentDetail(def.name(), def.description(), def.systemPrompt(),
                def.toolsOrDefault(), def.skillFlow() == null ? List.of() : def.skillFlow(),
                listSkillNames(def.name()));
    }

    private List<String> listSkillNames(String name) {
        Path skillsDir = agentRepository.skillsDirOf(name);
        if (!Files.isDirectory(skillsDir)) return List.of();
        try (Stream<Path> stream = Files.list(skillsDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("SKILL.md")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void validateAgentName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 名称不能为空");
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的 Agent 名称: " + name);
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 名称不得包含路径分隔符: " + name);
        }
    }

    private SkillInfo toSkillInfo(Path dir) {
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) return null;
        SkillZipInstaller.Frontmatter meta;
        try {
            meta = zipInstaller.parseFrontmatter(Files.readString(skillMd, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to read SKILL.md at {}", skillMd, e);
            return null;
        }
        String n = meta.name();
        if (n == null || n.isBlank()) n = dir.getFileName().toString();
        return new SkillInfo(n, meta.description() == null ? "" : meta.description(),
                dir.toAbsolutePath().toString(), listFiles(dir));
    }

    private List<String> listFiles(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private ZipEntryDataHolder findSkillMdEntry(SkillZipInstaller.ParsedZip parsed) {
        String normalized = parsed.skillMdEntry().replace('\\', '/');
        return parsed.entries().stream()
            .filter(e -> e.name().replace('\\', '/').equals(normalized))
            .findFirst()
            .map(e -> new ZipEntryDataHolder(e.name(), e.data()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取 SKILL.md 内容"));
    }

    private String readFrontmatterDescription(byte[] skillMdBytes) {
        SkillZipInstaller.Frontmatter meta =
                zipInstaller.parseFrontmatter(new String(skillMdBytes, StandardCharsets.UTF_8));
        return meta.description() == null ? "" : meta.description();
    }

    private record ZipEntryDataHolder(String name, byte[] data) { }

    public record AgentDetail(String name, String description, String systemPrompt,
            List<String> tools, List<String> skillFlow, List<String> skills) {
    }
}