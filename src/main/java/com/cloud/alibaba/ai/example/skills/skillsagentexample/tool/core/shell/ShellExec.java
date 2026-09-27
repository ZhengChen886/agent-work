package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.core.shell;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.SandboxPolicy;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shell 执行工具。
 *
 * 参数：
 * <ul>
 *   <li>command: 要执行的命令（必填,需通过沙箱校验）</li>
 *   <li>timeout_seconds: 超时秒数，默认 30(沙箱上限 max-timeout-seconds)</li>
 *   <li>working_dir: 工作目录（可选,必须在沙箱根目录内）</li>
 * </ul>
 *
 * 适用范围：仅用于本地文件操作、脚本执行、构建/编译/测试等本地任务。
 * 禁止使用本工具抓取网页或发起网络请求；获取网络内容请改用 web.search 工具。
 */
@Tool(name = "shell.exec", description = "执行本地 Shell 命令并返回输出（内部已按当前操作系统自动选择 cmd.exe 或 /bin/bash；仅限本地文件操作、脚本、构建/编译/测试；禁止用于 curl/wget 抓网页或任何网络请求，获取网络内容请改用 web.search）", category = "core.shell")
public class ShellExec extends BaseTool {

    @Autowired
    private SandboxPolicy sandboxPolicy;

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("command")) {
            throw new IllegalArgumentException("Missing required arg: command");
        }
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) throws Exception {
        String command = (String) args.get("command");
        int requestedTimeout = args.containsKey("timeout_seconds")
                ? ((Number) args.get("timeout_seconds")).intValue() : 0;
        int timeoutSeconds = sandboxPolicy.clampTimeout(requestedTimeout);
        String workingDir = args.containsKey("working_dir")
                ? (String) args.get("working_dir") : null;

        // 沙箱校验命令
        try {
            sandboxPolicy.assertCommandAllowed(command);
        } catch (SandboxPolicy.SandboxViolation sv) {
            return ToolResult.fail("Sandbox rejected: " + sv.getMessage(), 0);
        }

        // working_dir 必须在沙箱内
        if (workingDir != null && !workingDir.isBlank()) {
            try {
                Path wp = sandboxPolicy.assertReadable(workingDir);
                if (!Files.isDirectory(wp)) {
                    return ToolResult.fail("working_dir is not a directory: " + wp, 0);
                }
                workingDir = wp.toString();
            } catch (SandboxPolicy.SandboxViolation sv) {
                return ToolResult.fail("Sandbox rejected working_dir: " + sv.getMessage(), 0);
            }
        }

        ProcessBuilder pb = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            pb.command("cmd.exe", "/c", command);
        } else {
            pb.command("/bin/bash", "-c", command);
        }
        if (workingDir != null) {
            pb.directory(new java.io.File(workingDir));
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            return ToolResult.fail("Command timeout after " + timeoutSeconds + "s", timeoutSeconds * 1000L);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.exitValue();
        Map<String, Object> data = Map.of(
                "command", command,
                "exit_code", exitCode,
                "output", output,
                "timeout_seconds", timeoutSeconds
        );

        if (exitCode != 0) {
            return ToolResult.fail("Command exited with code " + exitCode + ": " + output, timeoutSeconds * 1000L);
        }
        return ToolResult.ok(data, timeoutSeconds * 1000L);
    }
}