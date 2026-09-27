package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.core.file;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.SandboxPolicy;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 删除文件/目录工具。
 *
 * 参数：
 * <ul>
 *   <li>path: 路径（必填,必须在沙箱根目录内）</li>
 *   <li>recursive: 目录是否递归删除，默认 false</li>
 * </ul>
 */
@Tool(name = "file.delete", description = "删除文件或目录", category = "core.file")
public class DeleteFile extends BaseTool {

    @Autowired
    private SandboxPolicy sandboxPolicy;

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("path")) {
            throw new IllegalArgumentException("Missing required arg: path");
        }
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) throws Exception {
        String pathStr = (String) args.get("path");
        boolean recursive = args != null && Boolean.TRUE.equals(args.get("recursive"));

        Path path;
        try {
            path = sandboxPolicy.assertDeletable(pathStr);
        } catch (SandboxPolicy.SandboxViolation sv) {
            return ToolResult.fail("Sandbox rejected: " + sv.getMessage(), 0);
        }

        if (!Files.exists(path)) {
            return ToolResult.fail("Path not found: " + path, 0);
        }

        if (Files.isDirectory(path)) {
            if (!recursive) {
                return ToolResult.fail("Path is a directory, set recursive=true to delete", 0);
            }
            try (Stream<Path> stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception e) {
                        log.warn("Failed to delete {}", p, e);
                    }
                });
            }
        } else {
            Files.delete(path);
        }

        return ToolResult.ok(Map.of("path", path.toString(), "deleted", true), 0);
    }
}