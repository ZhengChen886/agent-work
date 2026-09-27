package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.SandboxPolicy.SandboxViolation;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SandboxPolicyTest {

    private SandboxPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SandboxPolicy();
        policy.init();
    }

    @Test
    @DisplayName("默认配置:skills / agents / workflows / data / output 是合法根目录")
    void defaultRoots_allowListed() {
        String cwd = System.getProperty("user.dir");
        for (String sub : new String[]{
                "runtime/skills/foo.md",
                "runtime/agents/x.json",
                "runtime/workflows/w.json",
                "runtime/data/logs/app.log",
                "runtime/output/article.md"}) {
            Path allowed = assertDoesNotThrow(() -> policy.assertReadable(sub));
            assertNotNull(allowed);
            assertTrue(allowed.toString().contains(sub.replace('/', File.separatorChar)));
        }
        // sanity: cwd 路径出现在 allowed.toString
        assertNotNull(cwd);
    }

    @Test
    @DisplayName("路径外访问被沙箱拒绝")
    void outsideRoot_rejected() {
        SandboxViolation sv = assertThrows(SandboxViolation.class,
                () -> policy.assertReadable("C:/Windows/System32/drivers/etc/hosts"));
        assertEquals("file.read", sv.getOperation());
        assertTrue(sv.getMessage().contains("deny pattern") || sv.getMessage().contains("not within"));
    }

    @Test
    @DisplayName("deny-patterns 命中时直接拒绝,即使在白名单根目录内")
    void denyPattern_rejectedEvenInAllowedRoot() {
        SandboxPolicy custom = new SandboxPolicy();
        custom.setFile(custom.getFile());
        custom.getFile().setRoots(new java.util.ArrayList<>(java.util.List.of("skills")));
        custom.getFile().setDenyPatterns(new java.util.ArrayList<>(java.util.List.of("*.secret")));
        custom.init();
        SandboxViolation sv = assertThrows(SandboxViolation.class,
                () -> custom.assertReadable("skills/foo.secret"));
        assertTrue(sv.getMessage().contains("deny pattern"));
    }

    @Test
    @DisplayName("Shell 命令白名单:命中允许的命令")
    void shellWhitelist_allows() throws Exception {
        SandboxPolicy custom = new SandboxPolicy();
        custom.getShell().setAllowedCommands(java.util.List.of("ls", "cat"));
        custom.init();
        assertDoesNotThrow(() -> custom.assertCommandAllowed("ls -la"));
        assertDoesNotThrow(() -> custom.assertCommandAllowed("cat file.txt"));
    }

    @Test
    @DisplayName("Shell 命令白名单:非白名单命令拒绝")
    void shellWhitelist_rejectsUnknown() {
        SandboxPolicy custom = new SandboxPolicy();
        custom.getShell().setAllowedCommands(java.util.List.of("ls", "cat"));
        custom.init();
        SandboxViolation sv = assertThrows(SandboxViolation.class,
                () -> custom.assertCommandAllowed("curl https://example.com"));
        assertTrue(sv.getMessage().contains("not in whitelist"));
    }

    @Test
    @DisplayName("Shell 黑名单:rm -rf / 直接命中")
    void shellDenyPattern_strictMatch() {
        SandboxViolation sv = assertThrows(SandboxViolation.class,
                () -> policy.assertCommandAllowed("rm -rf /"));
        assertTrue(sv.getMessage().contains("deny pattern"));
    }

    @Test
    @DisplayName("timeout 被 clamp 到 max-timeout-seconds")
    void timeout_clamped() {
        int result = policy.clampTimeout(999);
        assertEquals(policy.getShell().getMaxTimeoutSeconds(), result);
        int result2 = policy.clampTimeout(null);
        assertEquals(policy.getShell().getDefaultTimeoutSeconds(), result2);
    }
}