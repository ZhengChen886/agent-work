package com.cloud.alibaba.ai.example.skills.skillsagentexample.security;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.admin.AdminProperties;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.admin.AdminProperties.AdminUser;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Admin 后台用户服务。
 *
 * <p>用户来源优先级:</p>
 * <ol>
 *   <li>环境变量 {@code ADMIN_USERS=admin:xxxx,ops:yyyy[,role1,role2]} —— 用于生产环境密钥注入</li>
 *   <li>{@code admin.users[*]} 配置列表 —— yml 中的显式配置</li>
 *   <li>回退到 {@code admin.username} / {@code admin.password} 默认账号 —— demo 用</li>
 * </ol>
 *
 * <p>用户表加载后保存在内存,Admin 后台独立 HttpServer 通过 {@code AuthenticationManager}
 * 在每次请求时校验 Basic 头。</p>
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserDetailsService.class);

    private static final String ENV_ADMIN_USERS = "ADMIN_USERS";

    private final AdminProperties properties;
    /** username -> (password, roles) */
    private final Map<String, Entry> users = new LinkedHashMap<>();

    public AdminUserDetailsService(AdminProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 重新加载用户表(供运行时热刷新)。 */
    public synchronized void reload() {
        users.clear();

        // 1. 环境变量优先级最高
        String env = System.getenv(ENV_ADMIN_USERS);
        if (env != null && !env.isBlank()) {
            for (String token : env.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                String[] parts = t.split(":", -1);
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    log.warn("Ignoring malformed ADMIN_USERS token: {}", t);
                    continue;
                }
                String u = parts[0].trim();
                String p = parts[1].trim();
                List<String> roles = parts.length >= 3
                        ? List.of(parts[2].trim().split("\\|"))
                        : List.of("ADMIN");
                addUser(u, p, roles);
                log.info("Admin user loaded from env: {}", u);
            }
            return;
        }

        // 2. yml admin.users
        List<AdminUser> list = properties.getUsers();
        if (list != null && !list.isEmpty()) {
            for (AdminUser au : list) {
                if (au.getUsername() == null || au.getUsername().isBlank()
                        || au.getPassword() == null || au.getPassword().isBlank()) {
                    log.warn("Ignoring admin user with blank username/password");
                    continue;
                }
                List<String> roles = au.getRoles() == null || au.getRoles().isEmpty()
                        ? List.of("ADMIN")
                        : au.getRoles();
                addUser(au.getUsername(), au.getPassword(), roles);
            }
            return;
        }

        // 3. 回退默认账号
        String u = properties.getUsername();
        String p = properties.getPassword();
        if (u != null && !u.isBlank() && p != null && !p.isBlank()) {
            addUser(u, p, List.of("ADMIN"));
            log.warn("Admin backend is using default credentials ({}). "
                    + "Set ADMIN_USERS env var or admin.users list for production.", u);
        }
    }

    private void addUser(String username, String password, List<String> roles) {
        users.put(username.toLowerCase(Locale.ROOT), new Entry(password, roles));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Entry e = users.get(username.toLowerCase(Locale.ROOT));
        if (e == null) {
            throw new UsernameNotFoundException("Admin user not found: " + username);
        }
        Collection<GrantedAuthority> auths = new ArrayList<>();
        for (String r : e.roles()) {
            auths.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase(Locale.ROOT)));
        }
        // DelegatingPasswordEncoder:如果密码已有 {bcrypt}/{noop} 前缀则直接使用,
        // 否则默认按明文(添加 {noop} 前缀)处理。
        String stored = e.password();
        if (stored != null && !stored.startsWith("{")) {
            stored = "{noop}" + stored;
        }
        return User.withUsername(username)
                .password(stored)
                .authorities(auths)
                .build();
    }

    public int userCount() {
        return users.size();
    }

    public List<String> usernames() {
        return Collections.unmodifiableList(new ArrayList<>(users.keySet()));
    }

    private record Entry(String password, List<String> roles) { }
}