package com.cloud.alibaba.ai.example.skills.skillsagentexample.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * 工具标记注解。
 * 标注了该注解的类会自动注册到 {@link ToolRegistry} 中。
 *
 * 用法：
 * <pre>
 * &#64;Tool(name = "file.read", description = "读取文件", category = "core.file")
 * public class ReadFile extends BaseTool { ... }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Tool {

    /** 工具名称（全局唯一，命名规范：分类.动作，如 file.read / shell.exec） */
    String name();

    /** 工具描述（暴露给大模型时使用） */
    String description();

    /** 工具分类（用于分组管理） */
    String category() default "default";

    /** 是否启用（默认 true） */
    boolean enabled() default true;

    /** 调用权限：public / agent / admin */
    String permission() default "public";
}