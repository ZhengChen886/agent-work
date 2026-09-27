package com.cloud.alibaba.ai.example.skills.skillsagentexample.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置。
 *
 * <p><b>主应用 8080</b> 显式 {@code permitAll},避免 Spring Security 默认拦截
 * 所有 API/静态资源(本项目是 demo,主应用保持匿名)。</p>
 *
 * <p><b>Admin 后台 8088</b> 是独立 {@code com.sun.net.httpserver.HttpServer},
 * 不走此 FilterChain,改由 {@code AdminAuthHandler} 在派发前
 * 调用本容器 {@link #authenticationManager} 进行 Basic Auth 校验。</p>
 *
 * <p>密码编码器使用 {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()}:
 * 明文密码会自动以 {@code {noop}} 前缀匹配,demo 默认账号无需手动哈希;
 * 生产环境管理员可在密码前加 {@code {bcrypt}$}2a$...} 走 BCrypt 路径。</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AdminUserDetailsService uds,
                                                      PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable());
        return http.build();
    }
}