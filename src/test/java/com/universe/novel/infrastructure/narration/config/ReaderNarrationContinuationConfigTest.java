package com.universe.novel.infrastructure.narration.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReaderNarrationContinuationConfig Unit Tests (MS-04.9H.7C2C3B)")
class ReaderNarrationContinuationConfigTest {

    @Test
    @DisplayName("Configures dedicated bounded TaskExecutor with AbortPolicy and without CallerRunsPolicy")
    void configuresDedicatedBoundedTaskExecutor() {
        ReaderNarrationContinuationConfig config = new ReaderNarrationContinuationConfig();
        TaskExecutor taskExecutor = config.readerNarrationContinuationTaskExecutor();

        assertThat(taskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) taskExecutor;

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("reader-narration-cont-");

        ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();
        assertThat(threadPoolExecutor.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class)
                .isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);

        executor.destroy();
    }
}
