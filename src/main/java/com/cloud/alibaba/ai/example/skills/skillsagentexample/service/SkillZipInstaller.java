package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * ZIP 形式的 Skill 安装器(共享给 {@link SkillService} 与 {@link AgentService})。
 *
 * <p>提供三个动作:</p>
 * <ul>
 *   <li>{@link #parse(MultipartFile, String)} —— 解析 ZIP,做大小/条目数/炸弹防护</li>
 *   <li>{@link #extractTo(ParsedZip, Path)} —— 把条目写到目标目录</li>
 *   <li>{@link #deleteRecursively(Path)} —— 出错回滚用</li>
 * </ul>
 *
 * <p>边界校验 + Frontmatter 解析 + 名称安全检查仍由调用方负责(每个调用方
 * 各自的 {@code root} 目录不同,解析出来的 skill 名称落点不同)。</p>
 */
@Component
public class SkillZipInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillZipInstaller.class);

    /**
     * 解析 ZIP。所有保护均通过 {@link ZipUploadLimits} 校验。
     *
     * @param file 上传的 ZIP
     * @param errorPrefix 错误信息前缀(用于区分 caller)
     */
    public ParsedZip parse(MultipartFile file, String errorPrefix) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorPrefix + ": 上传文件不能为空");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".zip")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorPrefix + ": 仅支持上传 .zip 压缩包");
        }

        List<ZipEntryData> entries = new ArrayList<>();
        String skillMdEntry = null;
        byte[] buffer = new byte[8192];
        long totalUnzipped = 0;

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                validateZipEntryName(entryName);
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                ZipUploadLimits.checkEntryCount(entries.size() + 1);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buffer)) != -1) {
                    bos.write(buffer, 0, n);
                }
                byte[] data = bos.toByteArray();
                ZipUploadLimits.checkTotalBytes(totalUnzipped + data.length);
                long compressed = Math.max(1L, entry.getCompressedSize());
                ZipUploadLimits.checkEntryRatio(compressed, data.length);
                totalUnzipped += data.length;

                entries.add(new ZipEntryData(entryName, data));
                skillMdEntry = pickSkillMarkdown(skillMdEntry, entryName);
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    errorPrefix + ": 无法读取 ZIP 文件: " + e.getMessage(), e);
        }

        if (skillMdEntry == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    errorPrefix + ": ZIP 内未找到 SKILL.md");
        }

        return new ParsedZip(entries, skillMdEntry);
    }

    /**
     * 把已解析的 ZIP 解压到目标目录(调用方已校验目标路径在沙箱内)。
     *
     * @return 解压后的根目录绝对路径
     */
    public Path extractTo(ParsedZip zip, Path target) {
        try {
            Files.createDirectories(target);
            String normalizedSkillMd = normalizeEntryName(zip.skillMdEntry);
            String prefix = "";
            int slash = normalizedSkillMd.lastIndexOf('/');
            if (slash >= 0) {
                prefix = normalizedSkillMd.substring(0, slash + 1);
            }

            for (ZipEntryData entry : zip.entries) {
                String normalized = normalizeEntryName(entry.name());
                String relative;
                if (prefix.isEmpty()) {
                    relative = normalized;
                } else {
                    if (!normalized.startsWith(prefix)) continue;
                    relative = normalized.substring(prefix.length());
                }
                if (relative.isEmpty()) continue;

                Path dest = target.resolve(relative).normalize();
                if (!dest.startsWith(target)) {
                    throw new IOException("ZIP 条目越界: " + entry.name());
                }
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                Files.write(dest, entry.data());
            }
            return target;
        } catch (IOException e) {
            try {
                deleteRecursively(target);
            } catch (IOException cleanupEx) {
                log.warn("Cleanup after extraction failure also failed: {}", cleanupEx.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "技能解压失败: " + e.getMessage(), e);
        }
    }

    /** 解析 ZIP 内的 SKILL.md frontmatter(name + description)。 */
    public Frontmatter parseFrontmatter(String content) {
        String name = null;
        String description = null;
        String currentKey = null;
        boolean inFrontmatter = false;
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.equals("---")) {
                if (inFrontmatter) break;
                inFrontmatter = true;
                continue;
            }
            if (!inFrontmatter) continue;
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if ("description".equals(currentKey) && !trimmed.isEmpty()) {
                    description = (description == null ? "" : description + " ") + trimmed;
                }
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                currentKey = key;
                if ("name".equals(key)) name = value;
                else if ("description".equals(key)) description = value.isEmpty() ? null : value;
            }
        }
        return new Frontmatter(name, description);
    }

    /** SKILL.md 内容 → name(已校验)。 */
    public String readSkillName(byte[] skillMdBytes) {
        Frontmatter meta = parseFrontmatter(new String(skillMdBytes, StandardCharsets.UTF_8));
        String name = meta.name();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKILL.md 中缺少 name 字段");
        }
        if (!isSafeSkillName(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的技能名称: " + name);
        }
        return name;
    }

    /** 安全名称校验。 */
    public boolean isSafeSkillName(String name) {
        if (name == null || name.isBlank()) return false;
        if (".".equals(name) || "..".equals(name)) return false;
        if (name.contains("/") || name.contains("\\")) return false;
        return true;
    }

    /** ZIP 条目名校验:拒绝绝对路径与 .. */
    public void validateZipEntryName(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ZIP 内存在空条目名");
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ZIP 内存在绝对路径: " + entryName);
        }
        if (entryName.matches("^[A-Za-z]:.*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ZIP 内存在绝对路径: " + entryName);
        }
        for (String part : normalized.split("/")) {
            if (part.equals("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ZIP 内存在非法路径: " + entryName);
            }
        }
    }

    public void deleteRecursively(Path target) throws IOException {
        try (var stream = Files.walk(target)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    public void deleteDirectoryQuietly(Path target) {
        try {
            deleteRecursively(target);
        } catch (IOException ignored) {
            log.warn("Failed to clean up partial skill directory: {}", target);
        }
    }

    private String normalizeEntryName(String entryName) {
        return entryName.replace('\\', '/');
    }

    private String pickSkillMarkdown(String current, String entryName) {
        String normalized = normalizeEntryName(entryName);
        if (!normalized.equals("SKILL.md") && !normalized.endsWith("/SKILL.md")) {
            return current;
        }
        if (current == null || normalized.length() < normalizeEntryName(current).length()) {
            return normalized;
        }
        return current;
    }

    /** ZIP 解析结果。 */
    public record ParsedZip(List<ZipEntryData> entries, String skillMdEntry) { }

    public record ZipEntryData(String name, byte[] data) { }

    public record Frontmatter(String name, String description) { }
}