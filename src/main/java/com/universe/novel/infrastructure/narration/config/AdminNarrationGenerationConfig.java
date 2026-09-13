package com.universe.novel.infrastructure.narration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Infrastructure configuration providing a dedicated bounded {@link TaskExecutor}
 * for Admin-initiated whole-chapter narration generation (MS-04.9H.7D4A).
 * <p>
 * <strong>Safety Invariants:</strong>
 * <ul>
 *     <li>Bounded thread pool (core: 2, max: 4) preventing unbounded thread creation.</li>
 *     <li>Bounded queue capacity (20) preventing out-of-memory pressure.</li>
 *     <li>{@link ThreadPoolExecutor.AbortPolicy} strictly rejecting surplus tasks upon saturation,
 *         guaranteeing that the caller thread (HTTP request thread) NEVER executes long-running TTS generation.</li>
 *     <li>Distinct named thread prefix for monitoring and isolation.</li>
 * </ul>
 */
@Configuration
public class AdminNarrationGenerationConfig {

    public static final String EXECUTOR_BEAN_NAME = "adminNarrationGenerationTaskExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public TaskExecutor adminNarrationGenerationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("admin-narration-gen-");
        // CRITICAL: Strictly reject on saturation rather than running on the caller/HTTP thread
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
