package com.cloud.alibaba.ai.example.skills.skillsagentexample.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Admin API 代理。
 *
 * <p>独立 HTTP 服务器（8088）收到 /api/admin/* 请求时，
 * 通过此代理直接调用主 Spring 容器里的 {@link AdminController} Bean。</p>
 */
@Component
@ConditionalOnProperty(prefix = "admin", name = "enabled", havingValue = "true")
public class AdminApiProxy {

    private final ApplicationContext ctx;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public AdminApiProxy(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 路由 + 调用对应方法，返回 JSON 字符串。
     */
    public String handle(String path, Map<String, String> query) {
        try {
            AdminController c = ctx.getBean(AdminController.class);
            Object result;
            switch (path == null ? "" : path) {
                case "":
                case "overview":
                    result = invoke(c, "overview");
                    break;
                case "tools":
                    result = invoke(c, "tools");
                    break;
                case "tools/info": {
                    String name = query.get("name");
                    result = invokeSingle(c, "toolInfo", String.class, name);
                    break;
                }
                case "tools/invoke":
                    // POST 请求的 body 在独立 HTTP server 中需要单独读取；这里暂用空实现
                    result = Map.of("info", "Use POST /api/admin/tools/invoke via main port 8080");
                    break;
                case "skills":
                    result = invoke(c, "skills");
                    break;
                case "metrics":
                    result = invoke(c, "metrics");
                    break;
                case "jvm":
                    result = invoke(c, "jvm");
                    break;
                case "config":
                    result = invoke(c, "config");
                    break;
                case "tools/test": {
                    String name = query.get("name");
                    Map<String, Object> args = new HashMap<>();
                    for (Map.Entry<String, String> e : query.entrySet()) {
                        if (!e.getKey().equals("name")) args.put(e.getKey(), e.getValue());
                    }
                    // 直接调用 testTool(String, Map) —— 使用反射
                    Method m = c.getClass().getMethod("testTool", String.class, Map.class);
                    result = m.invoke(c, name, args);
                    break;
                }
                default:
                    result = Map.of("error", "Not found: " + path);
            }
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            try {
                return mapper.writeValueAsString(Map.of("error", e.getMessage()));
            } catch (Exception ex) {
                return "{\"error\":\"serialize failed\"}";
            }
        }
    }

    private Object invoke(Object target, String methodName, Class<?>... paramTypes) throws Exception {
        Method m = target.getClass().getMethod(methodName, paramTypes);
        Object[] args = new Object[paramTypes.length];
        // 默认填充：String=null, Map=Map.of()
        if (paramTypes.length == 1 && paramTypes[0] == String.class) {
            args[0] = null;
        } else if (paramTypes.length == 2 && paramTypes[0] == String.class && paramTypes[1] == Map.class) {
            args[0] = null;
            args[1] = Map.of();
        }
        return m.invoke(target, args);
    }

    /** 单参数调用辅助 */
    private Object invokeSingle(Object target, String methodName, Class<?> pType, Object value) throws Exception {
        Method m = target.getClass().getMethod(methodName, pType);
        return m.invoke(target, value);
    }
}