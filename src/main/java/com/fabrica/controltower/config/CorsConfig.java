package com.fabrica.controltower.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin access to the API.
 *
 * <p>The frontend is served by Spring Boot itself
 * ({@code src/main/resources/static}), so in normal operation there is no
 * cross-origin request. This configuration exists for when the API
 * ({@code /api/v1}) is hosted separately from the corporate dashboard.</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final String[] ALLOWED_ORIGINS = {
            "http://localhost:8080",
            "http://localhost:8090",
            "http://localhost:3000",
            "http://localhost:5173",
            "http://127.0.0.1:8080"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
                .allowedOrigins(ALLOWED_ORIGINS)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Total-Count", "X-Computed-At")
                .allowCredentials(true)
                .maxAge(3600);
    }
}