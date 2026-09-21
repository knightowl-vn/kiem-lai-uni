package com.universe.interaction.infrastructure.maintenance;

import com.universe.interaction.application.retention.PurgeExpiredResolvedReportsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentReportRetentionSchedulerTest {

    @Mock
    private PurgeExpiredResolvedReportsUseCase useCase;

    @Test
    @DisplayName("A & C: scheduler invocation calls useCase.execute(configuredBatchSize) exactly once without second call")
    void shouldCallUseCaseExactlyOnceWithConfiguredBatchSize() {
        when(useCase.execute(50)).thenReturn(12);

        CommentReportRetentionScheduler scheduler = new CommentReportRetentionScheduler(useCase, 50);

        scheduler.runRetentionCleanup();

        verify(useCase, times(1)).execute(50);
    }

    @Test
    @DisplayName("B: batchSize <= 0 rejected at constructor boundary")
    void shouldRejectNonPositiveBatchSizeInConstructor() {
        assertThatThrownBy(() -> new CommentReportRetentionScheduler(useCase, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");

        assertThatThrownBy(() -> new CommentReportRetentionScheduler(useCase, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");
    }

    @Test
    @DisplayName("Constructor rejects null useCase")
    void shouldRejectNullUseCaseInConstructor() {
        assertThatThrownBy(() -> new CommentReportRetentionScheduler(null, 50))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("PurgeExpiredResolvedReportsUseCase cannot be null");
    }

    @Test
    @DisplayName("D: RuntimeException from use case is caught and handled by scheduler boundary without crashing or retrying")
    void shouldHandleExceptionGracefullyWithoutRetryLoop() {
        when(useCase.execute(50)).thenThrow(new IllegalStateException("Database connectivity failure"));

        CommentReportRetentionScheduler scheduler = new CommentReportRetentionScheduler(useCase, 50);

        assertThatCode(scheduler::runRetentionCleanup)
                .doesNotThrowAnyException();

        verify(useCase, times(1)).execute(50);
    }
}
