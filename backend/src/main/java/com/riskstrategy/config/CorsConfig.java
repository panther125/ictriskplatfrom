package com.riskstrategy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置：允许前端开发服务器访问。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许的来源模式（逗号分隔），默认放行本机任意端口的开发服务器，
     * 避免 Vite 端口漂移（5173/5174...）导致 403 Invalid CORS request。
     * 生产环境通过配置覆盖为固定前端域名。
     */
    @Value("${cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
