package com.exchange.marketmaker.config;

import com.exchange.marketmaker.interceptor.MmAuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMvc配置
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Bean
    public MmAuthInterceptor mmAuthInterceptor() {
        return new MmAuthInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(mmAuthInterceptor())
                .addPathPatterns("/api/v1/mm/batch-order/**")
                .addPathPatterns("/api/v1/mm/order/**")
                .excludePathPatterns("/api/v1/mm/apply")
                .excludePathPatterns("/api/v1/mm/status");
    }
}
