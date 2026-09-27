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
 * 写入文件工具。
 *
 * 参数：
 * <ul>
 *   <li>path: 文件路径（必填,必须在沙箱根目录内）</li>
 *   <li>content: 写入内容（必填,默认上限 5MB）</li>
 *   <li>append: 是否追加，默认 false</li>
 *   <li>encoding: 文件编码，默认 UTF-8</li>
 * </ul>
 */
@Tool(name = "file.write", description = "写入文件内容", category = "core.file")
public class WriteFile extends BaseTool {

    private static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024; // 5MB

    @Autowired
    private SandboxPolicy sandboxPolicy;

    @Override
    protected void validate(Map<String, Object> args) {
        if (args == null || !args.containsKey("path")) {
            throw new IllegalArgumentException("Missing required arg: path");
        }
        if (!args.containsKey("content")) {
            throw new IllegalArgumentException("Missing required arg: content");
        }
    }

    @Override
    protected ToolResult doExecute(Map<String, Object> args) throws Exception {
        String pathStr = (String) args.get("path");
        Object contentObj = args.get("content");
        boolean append = args.containsKey("append") && Boolean.TRUE.equals(args.get("append"));
        String encoding = args.containsKey("encoding")
                ? (String) args.get("encoding") : "UTF-8";

        // 沙箱校验(写入即视作可写)
        Path path;
        try {
            path = sandboxPolicy.assertWritable(pathStr);
        } catch (SandboxPolicy.SandboxViolation sv) {
            return ToolResult.fail("Sandbox rejected: " + sv.getMessage(), 0);
        }

        String content = contentObj == null ? "" : contentObj.toString();
        byte[] bytes = content.getBytes(encoding);
        if (bytes.length > DEFAULT_MAX_BYTES) {
            return ToolResult.fail("Content too large: " + bytes.length + " > max " + DEFAULT_MAX_BYTES + " bytes", 0);
        }

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        if (append) {
            Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } else {
            Files.write(path, bytes);
        }

        Map<String, Object> data = Map.of(
                "path", path.toString(),
                "size", bytes.length,
                "appended", append
        );
        return ToolResult.ok(data, 0);
    }
}