package com.openitsm.config;

import com.openitsm.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, SessionService sessions, ObjectMapper json,
        Environment env, @Value("${openitsm.cookie-name}") String cookieName, @Value("${openitsm.origin}") String origin,
        @Value("${openitsm.cookie-secure}") boolean secure) throws Exception {
        if (!secure && !env.matchesProfiles("local", "test")) throw new IllegalStateException("HTTP cookie requires local/test profile");
        if (secure && (!cookieName.equals("__Host-openitsm-session") || !origin.startsWith("https://")))
            throw new IllegalStateException("Production requires HTTPS and host cookie");
        var filter = new BrowserSecurityFilter(sessions, json, cookieName, origin, secure);
        // DB stores CSRF digests, so BrowserSecurityFilter performs Origin + constant-time digest validation.
        // No servlet HttpSession or default CookieCsrfTokenRepository is used.
        http.csrf(csrf -> csrf.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/v1/auth/**").permitAll();
                if (env.matchesProfiles("local")) auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                auth.requestMatchers("/api/v1/users/assignees").authenticated()
                    .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                    .requestMatchers("/api/**").authenticated().anyRequest().permitAll();
            })
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> GlobalExceptionHandler.write(json, req, res, new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录")))
                .accessDeniedHandler((req, res, ex) -> GlobalExceptionHandler.write(json, req, res, new BusinessException(403, "FORBIDDEN", "无操作权限"))));
        return http.build();
    }
}
