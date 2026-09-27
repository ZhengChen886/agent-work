package com.cloud.alibaba.ai.example.skills.skillsagentexample.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin 后台配置。
 *
 * <pre>
 * admin:
 *   enabled: true                    # 是否启用后台
 *   port: 8088                        # 后台独立端口
 *   username: admin                   # 单用户模式账号(向后兼容)
 *   password: admin123                # 单用户模式密码(向后兼容)
 *   users: []                         # 多用户列表 [{username, password, roles}]
 *   session-timeout-minutes: 60       # 会话超时
 * </pre>
 *
 * <p>多用户优先级:环境变量 {@code ADMIN_USERS=admin:xxxx,ops:yyyy} > {@code admin.users} > 默认账号。</p>
 */
@ConfigurationProperties(prefix = "admin")
public class AdminProperties {

    private boolean enabled = false;
    private int port = 8088;
    private String username = "admin";
    private String password = "admin123";
    private int sessionTimeoutMinutes = 60;

    /**
     * 多用户列表,每条 = username + password + 可选 roles(默认 ["ADMIN"])。
     */
    private List<AdminUser> users = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getSessionTimeoutMinutes() { return sessionTimeoutMinutes; }
    public void setSessionTimeoutMinutes(int v) { this.sessionTimeoutMinutes = v; }

    public List<AdminUser> getUsers() { return users; }
    public void setUsers(List<AdminUser> users) { this.users = users == null ? new ArrayList<>() : users; }

    public static class AdminUser {
        private String username;
        private String password;
        private List<String> roles = new ArrayList<>(List.of("ADMIN"));

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles == null ? new ArrayList<>() : roles; }
    }
}