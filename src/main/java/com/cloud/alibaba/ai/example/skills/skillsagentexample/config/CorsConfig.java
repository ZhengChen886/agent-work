package com.cloud.alibaba.ai.example.skills.skillsagentexample.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 配置。
 *
 * <p>三档策略(由 {@code spring.profiles.active} 与显式配置驱动):</p>
 * <ul>
 *   <li><b>dev profile</b> 或未显式配置:允许 {@code *} 任意 Origin;不允许凭据。</li>
 *   <li><b>prod profile + 未配置白名单</b>:拒绝跨域(只接受同源)。</li>
 *   <li><b>prod profile + {@code cors.allowed-origins}</b>:仅允许白名单 Origin;支持凭据。</li>
 * </ul>
 *
 * <p>注意:Spring/CORS 规范不允许 {@code allowCredentials=true} 配合 {@code *};
 * 本配置默认禁掉这种组合,杜绝浏览器警告。</p>
 */
@Configuration
public class CorsConfig {

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${cors.allowed-origins:}")
    private String allowedOriginsConfig;

    @Value("${cors.allowed-headers:*}")
    private String allowedHeadersConfig;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethodsConfig;

    @Value("${cors.allow-credentials:false}")
    private boolean allowCredentials;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        boolean isDev = isDevProfile();
        List<String> explicitOrigins = split(allowedOriginsConfig);

        if (!explicitOrigins.isEmpty()) {
            // 显式白名单模式
            config.setAllowedOrigins(explicitOrigins);
            config.setAllowCredentials(allowCredentials);
        } else if (isDev) {
            // dev 模式:允许任意 Origin,但禁凭据以遵守 CORS 规范
            config.addAllowedOriginPattern("*");
            config.setAllowCredentials(false);
        } else {
            // 生产无配置:仅同源
            config.setAllowedOrigins(Collections.emptyList());
            config.setAllowCredentials(false);
        }

        config.setAllowedHeaders(split(allowedHeadersConfig));
        config.setAllowedMethods(split(allowedMethodsConfig));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/admin/**", config);
        source.registerCorsConfiguration("/v1/**", config);

        return new CorsFilter(source);
    }

    private boolean isDevProfile() {
        if (!StringUtils.hasText(activeProfile)) return true;
        return Arrays.asList(activeProfile.split(","))
                .stream()
                .map(String::trim)
                .anyMatch(p -> p.equalsIgnoreCase("dev") || p.equalsIgnoreCase("default"));
    }

    private List<String> split(String csv) {
        if (!StringUtils.hasText(csv)) return Collections.emptyList();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}