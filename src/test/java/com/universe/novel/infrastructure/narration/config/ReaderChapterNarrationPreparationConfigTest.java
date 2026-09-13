package com.universe.novel.infrastructure.narration.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReaderChapterNarrationPreparationConfig Unit Tests (MS-04.9H.9, H.9I5E1)")
class ReaderChapterNarrationPreparationConfigTest {

    @Test
    @DisplayName("Configures dedicated bounded TaskExecutor with AbortPolicy and without CallerRunsPolicy")
    void configuresDedicatedBoundedTaskExecutor() {
        ReaderChapterNarrationPreparationConfig config = new ReaderChapterNarrationPreparationConfig();
        TaskExecutor taskExecutor = config.readerChapterNarrationPreparationTaskExecutor();

        assertThat(taskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) taskExecutor;

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("reader-chapter-narration-prep-");

        ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();
        assertThat(threadPoolExecutor.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class)
                .isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);

        executor.destroy();
    }
}
