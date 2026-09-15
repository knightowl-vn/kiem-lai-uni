package com.universe.novel.infrastructure.narration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Infrastructure configuration providing a shared bounded {@link TaskExecutor}
 * for whole-chapter narration generation across Admin and Reader domains (H.10A1).
 * <p>
 * <strong>Safety Invariants:</strong>
 * <ul>
 *     <li>Bounded thread pool (core: {@code maxConcurrentChapters}, max: {@code maxConcurrentChapters})
 *         guaranteeing that whole-chapter execution cannot exceed configured concurrency.</li>
 *     <li>Bounded queue capacity (default: 20) preventing out-of-memory pressure.</li>
 *     <li>{@link ThreadPoolExecutor.AbortPolicy} strictly rejecting surplus tasks upon saturation,
 *         guaranteeing that the caller thread (HTTP request thread) NEVER executes long-running TTS generation.</li>
 *     <li>Distinct named thread prefix ({@code chapter-narration-}) for monitoring and isolation.</li>
 * </ul>
 */
@Configuration
public class ChapterNarrationExecutionConfig {

    public static final String EXECUTOR_BEAN_NAME = "chapterNarrationExecutionTaskExecutor";

    private final int maxConcurrentChapters;
    private final int chapterQueueCapacity;

    public ChapterNarrationExecutionConfig(
            @Value("${novel.narration.concurrency.max-concurrent-chapters:1}") int maxConcurrentChapters,
            @Value("${novel.narration.concurrency.chapter-queue-capacity:20}") int chapterQueueCapacity
    ) {
        if (maxConcurrentChapters < 1) {
            throw new IllegalArgumentException(
                    "novel.narration.concurrency.max-concurrent-chapters must be >= 1, but found: " + maxConcurrentChapters
            );
        }
        if (chapterQueueCapacity < 0) {
            throw new IllegalArgumentException(
                    "novel.narration.concurrency.chapter-queue-capacity must be >= 0, but found: " + chapterQueueCapacity
            );
        }
        this.maxConcurrentChapters = maxConcurrentChapters;
        this.chapterQueueCapacity = chapterQueueCapacity;
    }

    @Bean(name = EXECUTOR_BEAN_NAME)
    public TaskExecutor chapterNarrationExecutionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrentChapters);
        executor.setMaxPoolSize(maxConcurrentChapters);
        executor.setQueueCapacity(chapterQueueCapacity);
        executor.setThreadNamePrefix("chapter-narration-");
        // CRITICAL: Strictly reject on saturation rather than running on the caller/HTTP thread
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
