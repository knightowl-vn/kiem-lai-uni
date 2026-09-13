package com.universe.novel.infrastructure.narration.maintenance;

import com.universe.novel.application.narration.ProcessPendingNarrationMediaCleanupUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("NarrationMaintenanceSchedulingConfig & Scheduler Wiring Tests")
class NarrationMaintenanceSchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    NarrationMaintenanceSchedulingConfig.class,
                    NarrationMediaCleanupScheduler.class
            )
            .withBean(ProcessPendingNarrationMediaCleanupUseCase.class, () -> mock(ProcessPendingNarrationMediaCleanupUseCase.class));

    @Test
    @DisplayName("1. Scheduler and scheduling config beans are absent by default when property is missing")
    void shouldBeDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(NarrationMaintenanceSchedulingConfig.class);
            assertThat(context).doesNotHaveBean(NarrationMediaCleanupScheduler.class);
        });
    }

    @Test
    @DisplayName("2. Scheduler and scheduling config beans are absent when property is false")
    void shouldBeDisabledWhenPropertyIsFalse() {
        contextRunner
                .withPropertyValues("novel.narration.media-cleanup.schedule.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NarrationMaintenanceSchedulingConfig.class);
                    assertThat(context).doesNotHaveBean(NarrationMediaCleanupScheduler.class);
                });
    }

    @Test
    @DisplayName("3. Scheduler and scheduling config beans are registered when property is true")
    void shouldBeEnabledWhenPropertyIsTrue() {
        contextRunner
                .withPropertyValues("novel.narration.media-cleanup.schedule.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(NarrationMaintenanceSchedulingConfig.class);
                    assertThat(context).hasSingleBean(NarrationMediaCleanupScheduler.class);
                });
    }

    @Test
    @DisplayName("4. Fails context startup when batch size is invalid (zero or negative)")
    void shouldFailWhenBatchSizeIsInvalid() {
        contextRunner
                .withPropertyValues(
                        "novel.narration.media-cleanup.schedule.enabled=true",
                        "novel.narration.media-cleanup.schedule.batch-size=0"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Batch size must be greater than 0, given: 0");
                });
    }

    @Test
    @DisplayName("5. Fails context startup when fixed delay is non-positive")
    void shouldFailWhenFixedDelayIsNonPositive() {
        contextRunner
                .withPropertyValues(
                        "novel.narration.media-cleanup.schedule.enabled=true",
                        "novel.narration.media-cleanup.schedule.fixed-delay=-5000"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }
}
