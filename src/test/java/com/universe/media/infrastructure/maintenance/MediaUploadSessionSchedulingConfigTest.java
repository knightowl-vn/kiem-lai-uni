package com.universe.media.infrastructure.maintenance;

import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.storage.SweepAbandonedMultipartUploadsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("MediaUploadSessionSchedulingConfig Tests (MS-08E2-L3-2)")
class MediaUploadSessionSchedulingConfigTest {

    private final MultipartStoragePort mockStoragePort = mock(MultipartStoragePort.class);
    private final SweepAbandonedMultipartUploadsUseCase mockUseCase = mock(SweepAbandonedMultipartUploadsUseCase.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    MediaUploadSessionSchedulingConfig.class,
                    MediaUploadSessionSweeperScheduler.class
            )
            .withBean(SweepAbandonedMultipartUploadsUseCase.class, () -> mockUseCase)
            .withBean(MultipartStoragePort.class, () -> mockStoragePort);

    @Test
    @DisplayName("Scheduler and scheduling config beans are absent by default when property is missing")
    void shouldBeDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MediaUploadSessionSchedulingConfig.class);
            assertThat(context).doesNotHaveBean(MediaUploadSessionSweeperScheduler.class);
            verifyNoInteractions(mockStoragePort);
            verifyNoInteractions(mockUseCase);
        });
    }

    @Test
    @DisplayName("Scheduler and scheduling config beans are absent when property is false")
    void shouldBeDisabledWhenPropertyIsFalse() {
        contextRunner
                .withPropertyValues("media.upload-session-cleanup.schedule.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MediaUploadSessionSchedulingConfig.class);
                    assertThat(context).doesNotHaveBean(MediaUploadSessionSweeperScheduler.class);
                    verifyNoInteractions(mockStoragePort);
                    verifyNoInteractions(mockUseCase);
                });
    }

    @Test
    @DisplayName("Scheduler and scheduling config beans are registered when property is true")
    void shouldBeEnabledWhenPropertyIsTrue() {
        contextRunner
                .withPropertyValues("media.upload-session-cleanup.schedule.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(MediaUploadSessionSchedulingConfig.class);
                    assertThat(context).hasSingleBean(MediaUploadSessionSweeperScheduler.class);
                });
    }

    @Test
    @DisplayName("Spring context startup with scheduler disabled performs no provider operations")
    void shouldPerformNoProviderOperationsWhenSchedulerDisabled() {
        contextRunner
                .withPropertyValues("media.upload-session-cleanup.schedule.enabled=false")
                .run(context -> {
                    verifyNoInteractions(mockStoragePort);
                });
    }
}
