package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.core.file;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.BaseTool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.SandboxPolicy;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.Tool;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 读取文件工具。
 *
 * 参数：
 * <ul>
 *   <li>path: 文件路径（必填,必须在沙箱根目录内）</li>
 *   <li>encoding: 文件编码，默认 UTF-8</li>
 *   <li>max_bytes: 最大读取字节数（默认 1MB,防止 OOM）</li>
 * </ul>
 */
@Tool(name = "file.read", description = "读取文件内容", category = "core.file")
public class ReadFile extends BaseTool {

    private static final long DEFAULT_MAX_BYTES = 1L * 1024 * 1024; // 1MB

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
        String encoding = args.containsKey("encoding")
                ? (String) args.get("encoding") : "UTF-8";
        long maxBytes = args.containsKey("max_bytes")
                ? ((Number) args.get("max_bytes")).longValue()
                : DEFAULT_MAX_BYTES;

        // 沙箱校验
        Path path;
        try {
            path = sandboxPolicy.assertReadable(pathStr);
        } catch (SandboxPolicy.SandboxViolation sv) {
            return ToolResult.fail("Sandbox rejected: " + sv.getMessage(), 0);
        }

        if (!Files.exists(path)) {
            return ToolResult.fail("File not found: " + path, 0);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.fail("Not a regular file: " + path, 0);
        }
        long size = Files.size(path);
        if (size > maxBytes) {
            return ToolResult.fail("File too large: " + size + " > max " + maxBytes + " bytes", 0);
        }

        byte[] bytes = Files.readAllBytes(path);
        String content = new String(bytes, encoding);
        Map<String, Object> data = Map.of(
                "path", path.toString(),
                "size", bytes.length,
                "encoding", encoding,
                "content", content
        );
        return ToolResult.ok(data, 0);
    }
}