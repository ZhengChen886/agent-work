package com.cloud.alibaba.ai.example.skills.skillsagentexample.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Admin 后台独立 HTTP 服务器(默认端口 8088)。
 *
 * <p>与主应用 (8080) 完全隔离:</p>
 * <ul>
 *   <li>独立端口(防火墙可独立配置)</li>
 *   <li>独立 HttpServer 实例,不共享 servlet 容器</li>
 *   <li>提供后台 UI 与管理 API 代理</li>
 * </ul>
 *
 * <p><b>鉴权</b>(自 Spring Security 引入):AdminAuthHandler 拦截
 * {@code /api/admin/*} 与 {@code /admin/*},在派发前用主容器
 * {@link AuthenticationManager} 校验 Basic Auth。失败返回 401 +
 * WWW-Authenticate;成功委派给 {@link ApiHandler} / {@link StaticHandler}。</p>
 */
@Component
@ConditionalOnProperty(prefix = "admin", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AdminProperties.class)
public class AdminServer {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);

    private final AdminProperties properties;
    private final AdminApiProxy apiProxy;
    private final AuthenticationManager authenticationManager;
    private HttpServer server;

    @Autowired
    public AdminServer(AdminProperties properties,
                       AdminApiProxy apiProxy,
                       AuthenticationManager authenticationManager) {
        this.properties = properties;
        this.apiProxy = apiProxy;
        this.authenticationManager = authenticationManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws IOException {
        if (!properties.isEnabled()) {
            log.info("Admin server disabled");
            return;
        }
        server = HttpServer.create(new InetSocketAddress(properties.getPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // 鉴权包装层:拦截 /api/admin/* 与 /admin/*
        AdminAuthHandler authWrapper = new AdminAuthHandler();

        // API 路径(走 auth -> ApiHandler)
        authWrapper.register("/api/admin/", new ApiHandler(apiProxy));

        // 静态资源(走 auth -> StaticHandler)
        authWrapper.register("/admin/", new StaticHandler());

        server.createContext("/api/admin/", authWrapper);
        server.createContext("/admin/", authWrapper);

        // 根路径 → 后台首页(让前端在浏览器里直接输入 localhost:8088 能跳走)
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "/admin/index.html");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        server.start();
        log.info("✓ Admin server listening on http://localhost:{}", properties.getPort());
        log.info("  Open: http://localhost:{}/admin/index.html", properties.getPort());
        log.info("  Auth: Basic Auth (configured via ADMIN_USERS env / admin.users)");
    }

    /**
     * 鉴权包装器:同一前缀下挂多个上下文,逐一尝试匹配路径;
     * 每个候选 handler 在被调用前都先做 Basic Auth 校验。
     */
    class AdminAuthHandler implements HttpHandler {
        private final Map<String, HttpHandler> delegates = new HashMap<>();

        void register(String prefix, HttpHandler delegate) {
            delegates.put(prefix, delegate);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!authenticate(exchange)) {
                    return; // authenticate 已写出 401
                }
                HttpHandler delegate = delegates.get(exchange.getRequestURI().getPath().startsWith("/admin/")
                        ? "/admin/"
                        : "/api/admin/");
                if (delegate == null) {
                    send(exchange, 404, "{\"error\":\"Not Found\"}");
                    return;
                }
                delegate.handle(exchange);
            } catch (Exception ex) {
                log.error("Admin handler failed", ex);
                if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                    send(exchange, 500, "{\"error\":\"internal_error\"}");
                }
            } finally {
                exchange.close();
            }
        }

        private boolean authenticate(HttpExchange exchange) throws IOException {
            // CORS 预检直放
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
                exchange.sendResponseHeaders(204, -1);
                return false;
            }

            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
                challenge(exchange);
                return false;
            }
            String token = header.substring(6).trim();
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                challenge(exchange);
                return false;
            }
            int idx = decoded.indexOf(':');
            if (idx < 0) {
                challenge(exchange);
                return false;
            }
            String user = decoded.substring(0, idx);
            String pass = decoded.substring(idx + 1);

            try {
                Authentication auth = authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(user, pass));
                exchange.setAttribute("admin.user", auth.getName());
                exchange.setAttribute("admin.authorities", auth.getAuthorities());
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
                return true;
            } catch (AuthenticationException e) {
                log.warn("Admin auth failed for user '{}': {}", user, e.getMessage());
                challenge(exchange);
                return false;
            }
        }

        private void challenge(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"admin\", charset=\"UTF-8\"");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            send(exchange, 401, "{\"error\":\"unauthorized\",\"message\":\"Basic Auth required\"}");
        }

        private void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /** 静态资源处理器(从 classpath:/static/admin/ 加载) */
    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/admin") || path.equals("/admin/")) {
                path = "/admin/index.html";
            }
            String resourcePath = "/static" + path;
            try (InputStream in = StaticHandler.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    String notFound = "404 Not Found: " + path;
                    exchange.sendResponseHeaders(404, notFound.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(notFound.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }
                byte[] bytes = in.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", guessContentType(path));
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    /** API 处理器(代理到 AdminController) */
    static class ApiHandler implements HttpHandler {
        private final AdminApiProxy proxy;

        ApiHandler(AdminApiProxy proxy) {
            this.proxy = proxy;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/admin/";
            String api = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
            // 去掉可能的尾部斜杠
            if (api.endsWith("/")) api = api.substring(0, api.length() - 1);

            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

            String body = proxy.handle(api, query);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private Map<String, String> parseQuery(String raw) {
            Map<String, String> map = new HashMap<>();
            if (raw == null || raw.isEmpty()) return map;
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
                    String v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                    map.put(k, v);
                }
            }
            return map;
        }
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}