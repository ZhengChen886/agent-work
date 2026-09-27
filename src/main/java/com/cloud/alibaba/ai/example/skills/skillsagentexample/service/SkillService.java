package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.SkillsAgent;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.SkillInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 顶层 Skill 管理服务,ZIP 安装由 {@link SkillZipInstaller} 统一处理。
 */
@Service
public class SkillService {
    private static final Logger logger = LoggerFactory.getLogger(SkillService.class);

    private final SkillsAgent skillsAgent;
    private final SkillZipInstaller zipInstaller;

    public SkillService(SkillsAgent skillsAgent, SkillZipInstaller zipInstaller) {
        this.skillsAgent = skillsAgent;
        this.zipInstaller = zipInstaller;
    }

    public List<SkillInfo> list() {
        Path skillsPath = Path.of(SkillsAgent.SKILLS_DIR).toAbsolutePath();
        if (!Files.isDirectory(skillsPath)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(skillsPath)) {
            return stream
                .filter(Files::isDirectory)
                .map(this::toSkillInfo)
                .filter(info -> info != null)
                .sorted(Comparator.comparing(SkillInfo::name))
                .toList();
        } catch (IOException e) {
            logger.error("Failed to list skills directory", e);
            return List.of();
        }
    }

    public SkillInfo upload(MultipartFile file) {
        SkillZipInstaller.ParsedZip parsed = zipInstaller.parse(file, "Skill 上传");
        ZipEntryDataHolder skillMdData = findSkillMdEntry(parsed);
        String name = zipInstaller.readSkillName(skillMdData.data());

        Path skillsRoot = Path.of(SkillsAgent.SKILLS_DIR).toAbsolutePath().normalize();
        Path target = skillsRoot.resolve(name).normalize();
        if (!target.startsWith(skillsRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的技能路径");
        }
        if (Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同名技能已存在: " + name);
        }

        zipInstaller.extractTo(parsed, target);
        skillsAgent.reload();
        logger.info("Skill uploaded and provider reloaded: {}", name);

        return list().stream()
            .filter(info -> info.name().equals(name))
            .findFirst()
            .orElse(new SkillInfo(name,
                readFrontmatterDescription(skillMdData.data()),
                target.toAbsolutePath().toString(),
                List.of()));
    }

    public void delete(String name) {
        if (!zipInstaller.isSafeSkillName(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的技能名称: " + name);
        }
        Path skillsRoot = Path.of(SkillsAgent.SKILLS_DIR).toAbsolutePath().normalize();
        Path target = skillsRoot.resolve(name).normalize();
        if (!target.startsWith(skillsRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的技能路径");
        }
        if (!Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在: " + name);
        }
        try {
            zipInstaller.deleteRecursively(target);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "删除技能失败: " + e.getMessage(), e);
        }
        skillsAgent.reload();
        logger.info("Skill deleted and provider reloaded: {}", name);
    }

    // ---- helpers ----

    private SkillInfo toSkillInfo(Path dir) {
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) return null;
        SkillZipInstaller.Frontmatter meta;
        try {
            meta = zipInstaller.parseFrontmatter(Files.readString(skillMd, StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Failed to read SKILL.md at {}", skillMd, e);
            return null;
        }
        String name = meta.name();
        if (name == null || name.isBlank()) name = dir.getFileName().toString();
        return new SkillInfo(name, meta.description() == null ? "" : meta.description(),
            dir.toAbsolutePath().toString(), listFiles(dir));
    }

    private List<String> listFiles(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
        } catch (IOException e) {
            logger.warn("Failed to list files in {}", dir, e);
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

    /** 内部小记录,避免在 controller 路径上多次重新查找 SKILL.md。 */
    private record ZipEntryDataHolder(String name, byte[] data) { }
}