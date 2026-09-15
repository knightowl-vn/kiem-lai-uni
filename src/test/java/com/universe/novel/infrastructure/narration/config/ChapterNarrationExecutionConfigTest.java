package com.universe.novel.infrastructure.narration.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterNarrationExecutionConfig Unit & Spring Context Tests (H.10A1)")
class ChapterNarrationExecutionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ChapterNarrationExecutionConfig.class);

    @Test
    @DisplayName("1. Spring context creates bean with default properties core=1, max=1, queue=20, and AbortPolicy")
    void springContextCreatesBeanWithDefaultProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChapterNarrationExecutionConfig.class);
            assertThat(context).hasBean("chapterNarrationExecutionTaskExecutor");

            TaskExecutor taskExecutor = context.getBean("chapterNarrationExecutionTaskExecutor", TaskExecutor.class);
            assertThat(taskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) taskExecutor;

            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getQueueCapacity()).isEqualTo(20);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("chapter-narration-");

            ThreadPoolExecutor rawExecutor = executor.getThreadPoolExecutor();
            assertThat(rawExecutor.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(rawExecutor.getRejectedExecutionHandler()).isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        });
    }

    @Test
    @DisplayName("2. Spring context honors overridden properties (e.g. maxConcurrentChapters=2, queue=50)")
    void springContextHonorsOverriddenProperties() {
        contextRunner
                .withPropertyValues(
                        "novel.narration.concurrency.max-concurrent-chapters=2",
                        "novel.narration.concurrency.chapter-queue-capacity=50"
                )
                .run(context -> {
                    TaskExecutor taskExecutor = context.getBean("chapterNarrationExecutionTaskExecutor", TaskExecutor.class);
                    assertThat(taskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
                    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) taskExecutor;

                    assertThat(executor.getCorePoolSize()).isEqualTo(2);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(2);
                    assertThat(executor.getQueueCapacity()).isEqualTo(50);
                });
    }

    @Test
    @DisplayName("3. Direct constructor with explicit parameters configures executor correctly")
    void directConstructorConfiguresExecutor() {
        ChapterNarrationExecutionConfig config = new ChapterNarrationExecutionConfig(1, 20);
        TaskExecutor taskExecutor = config.chapterNarrationExecutionTaskExecutor();

        assertThat(taskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) taskExecutor;

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(executor.getQueueCapacity()).isEqualTo(20);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("chapter-narration-");

        ThreadPoolExecutor rawExecutor = executor.getThreadPoolExecutor();
        assertThat(rawExecutor.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

        executor.destroy();
    }

    @Test
    @DisplayName("4. Configuration validation rejects maxConcurrentChapters < 1")
    void rejectsInvalidMaxConcurrentChapters() {
        assertThatThrownBy(() -> new ChapterNarrationExecutionConfig(0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-concurrent-chapters must be >= 1");

        assertThatThrownBy(() -> new ChapterNarrationExecutionConfig(-1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-concurrent-chapters must be >= 1");

        contextRunner
                .withPropertyValues("novel.narration.concurrency.max-concurrent-chapters=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("novel.narration.concurrency.max-concurrent-chapters must be >= 1, but found: 0");
                });
    }

    @Test
    @DisplayName("5. Configuration validation rejects chapterQueueCapacity < 0")
    void rejectsInvalidChapterQueueCapacity() {
        assertThatThrownBy(() -> new ChapterNarrationExecutionConfig(1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapter-queue-capacity must be >= 0");

        contextRunner
                .withPropertyValues("novel.narration.concurrency.chapter-queue-capacity=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("novel.narration.concurrency.chapter-queue-capacity must be >= 0, but found: -1");
                });
    }

    @Test
    @DisplayName("6. Constant bean name is chapterNarrationExecutionTaskExecutor")
    void constantBeanNameMatchesSpecification() {
        assertThat(ChapterNarrationExecutionConfig.EXECUTOR_BEAN_NAME)
                .isEqualTo("chapterNarrationExecutionTaskExecutor");
    }
}
