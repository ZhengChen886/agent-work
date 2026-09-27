package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

class SkillZipInstallerTest {

    private SkillZipInstaller installer;

    @BeforeEach
    void setUp() {
        installer = new SkillZipInstaller();
    }

    // ---- validateZipEntryName ----

    @Test
    @DisplayName("ZIP 条目名校验:绝对路径(/)拒绝")
    void zipEntryName_absolutePath_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> installer.validateZipEntryName("/etc/passwd"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("ZIP 条目名校验:绝对路径(Windows 盘符)拒绝")
    void zipEntryName_driveLetter_rejected() {
        assertThrows(ResponseStatusException.class,
                () -> installer.validateZipEntryName("C:\\Windows\\System32"));
        assertThrows(ResponseStatusException.class,
                () -> installer.validateZipEntryName("D:/foo/bar"));
    }

    @Test
    @DisplayName("ZIP 条目名校验:.. 跳出拒绝")
    void zipEntryName_parentTraversal_rejected() {
        assertThrows(ResponseStatusException.class,
                () -> installer.validateZipEntryName("../etc/passwd"));
        assertThrows(ResponseStatusException.class,
                () -> installer.validateZipEntryName("foo/../bar"));
    }

    @Test
    @DisplayName("ZIP 条目名校验:空名拒绝")
    void zipEntryName_empty_rejected() {
        assertThrows(ResponseStatusException.class,
                () -> installer.validateZipEntryName(""));
        assertThrows(ResponseStatusException.class,
                () -> installer.validateZipEntryName(null));
    }

    @Test
    @DisplayName("ZIP 条目名校验:普通相对路径放行")
    void zipEntryName_normal_allowed() {
        installer.validateZipEntryName("SKILL.md");
        installer.validateZipEntryName("scripts/main.py");
    }

    // ---- isSafeSkillName ----

    @Test
    @DisplayName("Skill 名称安全校验:边界值")
    void safeSkillName_edgeCases() {
        assertTrue(installer.isSafeSkillName("arxiv-search"));
        assertTrue(installer.isSafeSkillName("web_search.v2"));
        assertFalse(installer.isSafeSkillName("."));
        assertFalse(installer.isSafeSkillName(".."));
        assertFalse(installer.isSafeSkillName("a/b"));
        assertFalse(installer.isSafeSkillName("a\\b"));
        assertFalse(installer.isSafeSkillName(""));
        assertFalse(installer.isSafeSkillName(null));
    }

    // ---- parseFrontmatter ----

    @Test
    @DisplayName("Frontmatter 解析:name + 单行 description")
    void frontmatter_simple() {
        String content = "---\nname: arxiv-search\ndescription: 搜索 arXiv 预印本论文库\n---\n# Body";
        SkillZipInstaller.Frontmatter meta = installer.parseFrontmatter(content);
        assertEquals("arxiv-search", meta.name());
        assertEquals("搜索 arXiv 预印本论文库", meta.description());
    }

    @Test
    @DisplayName("Frontmatter 解析:多行 description 折叠")
    void frontmatter_multilineDescription() {
        String content = "---\nname: foo\ndescription: 第一行\n  第二行\n  第三行\n---\n# Body";
        SkillZipInstaller.Frontmatter meta = installer.parseFrontmatter(content);
        assertEquals("foo", meta.name());
        assertEquals("第一行 第二行 第三行", meta.description());
    }

    @Test
    @DisplayName("Frontmatter 解析:缺 name 字段返回 null")
    void frontmatter_missingName() {
        String content = "---\ndescription: only description\n---\n";
        SkillZipInstaller.Frontmatter meta = installer.parseFrontmatter(content);
        assertEquals(null, meta.name());
        assertEquals("only description", meta.description());
    }

    // ---- parse (with multipart ZIP) ----

    @Test
    @DisplayName("parse:合法 ZIP 返回 entries + skillMdEntry")
    void parse_validZip(@TempDir Path tmp) throws IOException {
        byte[] zipBytes = makeZip(tmp, "demo-skill",
                "SKILL.md", "---\nname: demo-skill\ndescription: 演示技能\n---\n# Hi",
                "scripts/main.py", "print('hello')");
        MultipartFile mf = new MockMultipartFile(
                "file", "demo.zip", "application/zip", zipBytes);

        SkillZipInstaller.ParsedZip parsed = installer.parse(mf, "test");
        assertEquals("demo-skill/SKILL.md", parsed.skillMdEntry());
        assertEquals(2, parsed.entries().size());
    }

    @Test
    @DisplayName("parse:非 ZIP 后缀拒绝")
    void parse_nonZip_rejected() {
        MultipartFile mf = new MockMultipartFile(
                "file", "demo.txt", "text/plain", "not a zip".getBytes(StandardCharsets.UTF_8));
        assertThrows(ResponseStatusException.class,
                () -> installer.parse(mf, "test"));
    }

    @Test
    @DisplayName("parse:ZIP 内缺 SKILL.md 拒绝")
    void parse_missingSkillMd(@TempDir Path tmp) throws IOException {
        byte[] zipBytes = makeZip(tmp, null, "main.py", "print('hi')");
        MultipartFile mf = new MockMultipartFile(
                "file", "demo.zip", "application/zip", zipBytes);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> installer.parse(mf, "test"));
        assertTrue(ex.getMessage().contains("未找到 SKILL.md"));
    }

    @Test
    @DisplayName("parse:绝对路径条目拒绝")
    void parse_zipWithAbsolutePath_rejected(@TempDir Path tmp) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            ZipEntry e = new ZipEntry("/etc/passwd");
            zos.putNextEntry(e);
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        MultipartFile mf = new MockMultipartFile(
                "file", "demo.zip", "application/zip", bos.toByteArray());
        assertThrows(ResponseStatusException.class,
                () -> installer.parse(mf, "test"));
    }

    @Test
    @DisplayName("extractTo:成功解压 SKILL.md 与 scripts/")
    void extractTo_success(@TempDir Path tmp) throws IOException {
        byte[] zipBytes = makeZip(tmp, "demo",
                "SKILL.md", "---\nname: demo\n---\n# x",
                "scripts/main.py", "print('hi')");
        MultipartFile mf = new MockMultipartFile(
                "file", "demo.zip", "application/zip", zipBytes);
        SkillZipInstaller.ParsedZip parsed = installer.parse(mf, "test");

        Path target = tmp.resolve("demo").normalize();
        Path out = installer.extractTo(parsed, target);

        assertEquals(target, out);
        assertTrue(Files.exists(target.resolve("SKILL.md")));
        assertTrue(Files.exists(target.resolve("scripts/main.py")));
    }

    // ---- helpers ----

    private byte[] makeZip(Path tmp, String nameInZip, String... entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            String prefix = nameInZip == null ? "" : nameInZip + "/";
            for (int i = 0; i < entries.length; i += 2) {
                String entryName = prefix + entries[i];
                byte[] content = entries[i + 1].getBytes(StandardCharsets.UTF_8);
                ZipEntry e = new ZipEntry(entryName);
                zos.putNextEntry(e);
                zos.write(content);
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}