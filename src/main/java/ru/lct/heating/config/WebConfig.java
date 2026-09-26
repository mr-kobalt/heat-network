package ru.lct.heating.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS для опционального фронтенд-визуализатора (ADR-0013) и executor для
 * асинхронного стриминга ответов (результат/этапы, ADR-0016).
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private static final int MVC_ASYNC_TIMEOUT_MS = 300_000;

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.getAllowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    /**
     * Ограниченный пул для {@code StreamingResponseBody}: без него Spring MVC
     * использует {@code SimpleAsyncTaskExecutor} (поток на запрос) и пишет
     * предупреждение про {@code Callable} под нагрузкой.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("mvc-");
        executor.initialize();
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(MVC_ASYNC_TIMEOUT_MS);
    }
}
