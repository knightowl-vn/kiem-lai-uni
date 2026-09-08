package com.universe.novel.infrastructure.narration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Infrastructure configuration providing a dedicated bounded {@link TaskExecutor}
 * for background reader narration continuation (MS-04.9H.7C2C3B).
 * <p>
 * <strong>Safety Invariant:</strong>
 * Uses {@link ThreadPoolExecutor.AbortPolicy} to strictly reject surplus tasks when the queue is saturated,
 * ensuring the caller thread (HTTP request) NEVER executes background TTS/Media continuation work.
 */
@Configuration
public class ReaderNarrationContinuationConfig {

    public static final String EXECUTOR_BEAN_NAME = "readerNarrationContinuationTaskExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public TaskExecutor readerNarrationContinuationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("reader-narration-cont-");
        // CRITICAL: Must reject/abort on saturation rather than executing on the caller thread
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
