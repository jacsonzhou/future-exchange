package com.exchange.user.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置
 * 
 * 注意：Token认证已在 API Gateway 统一处理
 * user-core 只负责 Token 生成和管理
 * 用户信息通过 Header 从 Gateway 透传
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF
            .csrf(AbstractHttpConfigurer::disable)
            // 配置授权规则
            .authorizeHttpRequests(auth -> auth
                // 允许匿名访问的接口
                .requestMatchers("/api/v1/user/register", "/api/v1/user/login", "/actuator/**").permitAll()
                // 其他请求需要认证
                .anyRequest().authenticated()
            )
            // 禁用表单登录
            .formLogin(AbstractHttpConfigurer::disable)
            // 禁用 HTTP Basic
            .httpBasic(AbstractHttpConfigurer::disable);
        
        return http.build();
    }
    
    // TokenFilter 已移除，认证在 API Gateway (8080) 统一处理
    // user-core 接收到的请求已经过 Gateway 认证
    // 用户信息通过 Header 获取：
    //   - X-User-Id
    //   - X-Account-Id
    //   - X-Username
}
