package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具沙箱策略。
 *
 * <p>为文件工具限制允许的根目录,为 Shell 工具限制命令前缀/黑名单/超时。</p>
 *
 * <pre>
 * sandbox:
 *   file:
 *     roots:           # 允许的根目录(绝对或相对 cwd)
 *       - runtime/skills
 *       - runtime/agents
 *       - runtime/workflows
 *       - runtime/data
 *       - runtime/output
 *       - work-dir
 *     deny-patterns:   # 拒绝的路径 glob(子串匹配)
 *       - "*\.ssh*"
 *       - "*\\.git/*"
 *   shell:
 *     allowed-commands:   # 命令白名单(若非空,只允许这些前缀)
 *     denied-patterns:    # 拒绝的子串(rm -rf /, mkfs 等)
 *       - "rm -rf /"
 *       - "mkfs"
 *       - "format "
 *     default-timeout-seconds: 30
 *     max-timeout-seconds: 300
 * </pre>
 *
 * <p>若未显式配置,使用代码内默认(root 包含 runtime/skills、runtime/agents、runtime/workflows、runtime/data、runtime/output,shell 仅黑名单)。</p>
 */
@Component
@ConfigurationProperties(prefix = "sandbox")
public class SandboxPolicy {

    private static final Logger log = LoggerFactory.getLogger(SandboxPolicy.class);

    private FilePolicy file = new FilePolicy();
    private ShellPolicy shell = new ShellPolicy();

    public FilePolicy getFile() { return file; }
    public void setFile(FilePolicy file) { this.file = file == null ? new FilePolicy() : file; }

    public ShellPolicy getShell() { return shell; }
    public void setShell(ShellPolicy shell) { this.shell = shell == null ? new ShellPolicy() : shell; }

    @PostConstruct
    public void init() {
        if (file.getRoots() == null || file.getRoots().isEmpty()) {
            file.setRoots(new ArrayList<>(List.of("runtime/skills", "runtime/agents", "runtime/workflows", "runtime/data", "runtime/output")));
        }
        if (file.getDenyPatterns() == null || file.getDenyPatterns().isEmpty()) {
            file.setDenyPatterns(new ArrayList<>());
        }
        if (shell.getDeniedPatterns() == null || shell.getDeniedPatterns().isEmpty()) {
            shell.setDeniedPatterns(new ArrayList<>(List.of(
                    "rm -rf /", "mkfs", "format ",
                    "del /f /s /q C:\\\\",
                    ":(){:|:&};:")));
        }
        if (shell.getAllowedCommands() == null) {
            shell.setAllowedCommands(new ArrayList<>());
        }
        if (shell.getDefaultTimeoutSeconds() <= 0) shell.setDefaultTimeoutSeconds(30);
        if (shell.getMaxTimeoutSeconds() <= 0) shell.setMaxTimeoutSeconds(300);
        log.info("Sandbox policy initialized: {} file roots, {} shell deny patterns",
                file.getRoots().size(), shell.getDeniedPatterns().size());
    }

    // ============== 文件策略 ==============

    /** 把目标路径解析为绝对路径,验证在允许根目录内且不命中拒绝模式。 */
    public Path resolveAndCheck(String rawPath, String operation) throws SandboxViolation {
        if (rawPath == null || rawPath.isBlank()) {
            throw new SandboxViolation(operation, "<empty>", "Path is empty");
        }
        Path target = Path.of(rawPath).toAbsolutePath().normalize();
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        // 黑名单子串匹配(优先)
        String targetStr = target.toString();
        for (String deny : file.getDenyPatterns()) {
            if (matches(targetStr, deny)) {
                throw new SandboxViolation(operation, targetStr, "deny pattern: " + deny);
            }
        }

        // 白名单根目录(任一包含即放行)
        boolean allowed = false;
        for (String root : file.getRoots()) {
            Path rp = Path.of(root);
            if (!rp.isAbsolute()) rp = cwd.resolve(root).toAbsolutePath().normalize();
            Path check = rp;
            // 也接受显式绝对路径
            if (Files.exists(check) && Files.isDirectory(check)) {
                if (target.startsWith(check)) {
                    allowed = true;
                    break;
                }
            } else {
                // 兜底:即便根目录不存在,也允许同名路径前缀(避免目录未创建就报错)
                if (target.toString().startsWith(check.toString())) {
                    allowed = true;
                    break;
                }
            }
        }

        if (!allowed) {
            throw new SandboxViolation(operation, targetStr,
                    "Path is not within any allowed root: " + file.getRoots());
        }
        return target;
    }

    /** 校验目录白名单(用于 ListFiles)。允许路径在白名单根目录内即可列出。 */
    public Path assertReadable(String rawPath) throws SandboxViolation {
        return resolveAndCheck(rawPath, "file.read");
    }

    /** 校验可写路径。 */
    public Path assertWritable(String rawPath) throws SandboxViolation {
        return resolveAndCheck(rawPath, "file.write");
    }

    /** 校验可删除路径(写权限就足够了)。 */
    public Path assertDeletable(String rawPath) throws SandboxViolation {
        return resolveAndCheck(rawPath, "file.delete");
    }

    // ============== Shell 策略 ==============

    /**
     * 校验 shell 命令。
     * @throws SandboxViolation 如果命中黑名单或不在白名单前缀中
     */
    public void assertCommandAllowed(String command) throws SandboxViolation {
        if (command == null || command.isBlank()) {
            throw new SandboxViolation("shell.exec", "<empty>", "Command is empty");
        }
        // 黑名单(子串匹配,大小写敏感以避免绕过)
        for (String deny : shell.getDeniedPatterns()) {
            if (command.contains(deny)) {
                throw new SandboxViolation("shell.exec", command, "deny pattern: " + deny);
            }
        }
        // 白名单(若非空,命令必须以白名单前缀之一开头)
        if (!shell.getAllowedCommands().isEmpty()) {
            String firstWord = command.trim().split("\\s+", 2)[0].toLowerCase();
            boolean ok = shell.getAllowedCommands().stream()
                    .map(String::toLowerCase)
                    .anyMatch(firstWord::startsWith);
            if (!ok) {
                throw new SandboxViolation("shell.exec", command,
                        "command not in whitelist: " + shell.getAllowedCommands());
            }
        }
    }

    public int clampTimeout(Integer requested) {
        if (requested == null || requested <= 0) return shell.getDefaultTimeoutSeconds();
        return Math.min(requested, shell.getMaxTimeoutSeconds());
    }

    // ============== Helpers ==============

    private boolean matches(String text, String pattern) {
        if (pattern == null || pattern.isEmpty()) return false;
        // 支持 glob-like (* 任意字符)
        String regex = Arrays.stream(pattern.split("\\*"))
                .map(s -> java.util.regex.Pattern.quote(s))
                .reduce((a, b) -> a + ".*" + b)
                .orElse("");
        return Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE).matcher(text).matches();
    }

    // ============== DTO ==============

    public static class FilePolicy {
        private List<String> roots = new ArrayList<>();
        private List<String> denyPatterns = new ArrayList<>();

        public List<String> getRoots() { return roots; }
        public void setRoots(List<String> roots) { this.roots = roots; }

        public List<String> getDenyPatterns() { return denyPatterns; }
        public void setDenyPatterns(List<String> denyPatterns) { this.denyPatterns = denyPatterns; }
    }

    public static class ShellPolicy {
        private List<String> allowedCommands = new ArrayList<>();
        private List<String> deniedPatterns = new ArrayList<>();
        private int defaultTimeoutSeconds = 30;
        private int maxTimeoutSeconds = 300;

        public List<String> getAllowedCommands() { return allowedCommands; }
        public void setAllowedCommands(List<String> v) { this.allowedCommands = v; }

        public List<String> getDeniedPatterns() { return deniedPatterns; }
        public void setDeniedPatterns(List<String> v) { this.deniedPatterns = v; }

        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int v) { this.defaultTimeoutSeconds = v; }

        public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
        public void setMaxTimeoutSeconds(int v) { this.maxTimeoutSeconds = v; }
    }

    /** 沙箱拒绝异常,工具调用方应捕获并转为 {@link ToolResult#fail}。 */
    public static class SandboxViolation extends Exception {
        private final String operation;
        private final String target;
        public SandboxViolation(String operation, String target, String message) {
            super(message);
            this.operation = operation;
            this.target = target;
        }
        public String getOperation() { return operation; }
        public String getTarget() { return target; }
    }

    /** 不可变只读 Set,用于外部查询。 */
    public Set<String> allowedRoots() {
        return Collections.unmodifiableSet(new HashSet<>(file.getRoots()));
    }
}