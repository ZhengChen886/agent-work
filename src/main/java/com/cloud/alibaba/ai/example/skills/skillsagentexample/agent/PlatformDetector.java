package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import org.springframework.stereotype.Component;

/**
 * 运行平台探测器。
 *
 * <p>在 Agent 构建时判断当前 JVM 运行于 Windows / macOS / Linux，并给出推荐的
 * Shell 执行器与命令语法。探测结果通过 {@link #buildPromptHint()} 动态注入系统
 * 提示词，使模型在调用 shell.exec 前就明确当前环境，避免生成跨平台不兼容的命令。</p>
 */
@Component
public class PlatformDetector {

    public enum Platform { WINDOWS, MACOS, LINUX, OTHER }

    private final String osName;
    private final Platform platform;

    public PlatformDetector() {
        this.osName = System.getProperty("os.name", "unknown");
        String os = osName.toLowerCase();
        if (os.contains("windows")) {
            this.platform = Platform.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            this.platform = Platform.MACOS;
        } else if (os.contains("linux")) {
            this.platform = Platform.LINUX;
        } else {
            this.platform = Platform.OTHER;
        }
    }

    public Platform platform() {
        return platform;
    }

    public String osName() {
        return osName;
    }

    public boolean isWindows() {
        return platform == Platform.WINDOWS;
    }

    public boolean isUnixLike() {
        return platform == Platform.MACOS || platform == Platform.LINUX;
    }

    /**
     * 推荐使用的 Shell 执行器。
     */
    public String recommendedShell() {
        return switch (platform) {
            case WINDOWS -> "cmd.exe / PowerShell";
            case MACOS, LINUX -> "bash (/bin/bash -c)";
            default -> "bash (/bin/bash -c)";
        };
    }

    /**
     * 生成注入系统提示词的运行环境说明段落。
     */
    public String buildPromptHint() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 运行环境（Shell 命令必读）\n");
        sb.append("- 当前操作系统：").append(osName).append('\n');
        if (platform == Platform.WINDOWS) {
            sb.append("- 推荐工具：shell.exec 底层已用 cmd.exe /c 执行，请使用 Windows 命令语法（如 dir、type、findstr、set）；")
              .append("确需 PowerShell 特性时，可显式发送命令 `powershell -Command \"...\"`。\n");
        } else if (platform == Platform.MACOS || platform == Platform.LINUX) {
            sb.append("- 推荐工具：shell.exec 底层已用 /bin/bash -c 执行，请使用 bash（POSIX）语法（如 ls、cat、grep、sed）。\n");
        } else {
            sb.append("- 推荐工具：shell.exec 底层回退为 /bin/bash -c，请使用 bash 语法。\n");
        }
        sb.append("- 生成任何 shell 命令前，先按上述环境选择对应语法，禁止混用 Windows 与 Unix 命令。\n");
        return sb.toString();
    }
}