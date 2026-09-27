package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.core.file;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.SandboxPolicy;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 列出目录文件工具。
 *
 * 参数：
 * <ul>
 *   <li>path: 目录路径（必填，默认当前目录;必须在沙箱根目录内）</li>
 *   <li>recursive: 是否递归，默认 false</li>
 *   <li>pattern: 文件名匹配模式（glob 风格，可选）</li>
 * </ul>
 */
@Tool(name = "file.list", description = "列出目录中的文件", category = "core.file")
public class ListFiles extends BaseTool {

    @Autowired
    private SandboxPolicy sandboxPolicy;

    @Override
    protected ToolResult doExecute(Map<String, Object> args) throws Exception {
        String pathStr = args != null && args.containsKey("path")
                ? (String) args.get("path") : ".";
        boolean recursive = args != null && Boolean.TRUE.equals(args.get("recursive"));
        String pattern = args != null ? (String) args.get("pattern") : null;

        Path path;
        try {
            path = sandboxPolicy.assertReadable(pathStr);
        } catch (SandboxPolicy.SandboxViolation sv) {
            return ToolResult.fail("Sandbox rejected: " + sv.getMessage(), 0);
        }

        if (!Files.exists(path)) {
            return ToolResult.fail("Directory not found: " + path, 0);
        }
        if (!Files.isDirectory(path)) {
            return ToolResult.fail("Not a directory: " + path, 0);
        }

        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> stream = recursive ? Files.walk(path) : Files.list(path)) {
            stream
                    .filter(p -> pattern == null || p.getFileName().toString().matches(pattern))
                    .forEach(p -> {
                        try {
                            Map<String, Object> info = new java.util.HashMap<>();
                            info.put("name", p.getFileName().toString());
                            info.put("path", p.toString());
                            info.put("is_directory", Files.isDirectory(p));
                            info.put("size", Files.isRegularFile(p) ? Files.size(p) : 0);
                            files.add(info);
                        } catch (Exception e) {
                            log.warn("Failed to read file info: {}", p, e);
                        }
                    });
        }

        Map<String, Object> data = Map.of(
                "path", path.toString(),
                "count", files.size(),
                "files", files
        );
        return ToolResult.ok(data, 0);
    }
}