package com.keplura.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the M1a request-logging convention for the whole API surface.
 * The liveness endpoint is excluded on purpose: probes run very frequently and
 * would add no operational signal.
 */
@Configuration
public class RequestLoggingConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequestLoggingInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/health");
    }
}
