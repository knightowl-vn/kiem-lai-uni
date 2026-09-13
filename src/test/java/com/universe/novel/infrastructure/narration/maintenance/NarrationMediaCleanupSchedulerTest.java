package com.universe.novel.infrastructure.narration.maintenance;

import com.universe.novel.application.narration.ProcessPendingNarrationMediaCleanupResult;
import com.universe.novel.application.narration.ProcessPendingNarrationMediaCleanupUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NarrationMediaCleanupScheduler Unit Tests")
class NarrationMediaCleanupSchedulerTest {

    @Mock
    private ProcessPendingNarrationMediaCleanupUseCase cleanupUseCase;

    @Test
    @DisplayName("1. Invokes cleanup use case with configured batch size on schedule trigger")
    void shouldInvokeCleanupUseCaseWithConfiguredBatchSize() {
        NarrationMediaCleanupScheduler scheduler =
                new NarrationMediaCleanupScheduler(cleanupUseCase, 30);

        when(cleanupUseCase.execute(30))
                .thenReturn(new ProcessPendingNarrationMediaCleanupResult(5, 5, 0, Instant.now()));

        scheduler.cleanupPendingNarrationMedia();

        verify(cleanupUseCase).execute(30);
    }

    @Test
    @DisplayName("2. Scheduler does not contain cleanup decision logic and delegates directly to use case")
    void shouldDelegateDirectlyWithoutDecisionLogic() {
        NarrationMediaCleanupScheduler scheduler =
                new NarrationMediaCleanupScheduler(cleanupUseCase, 50);

        when(cleanupUseCase.execute(50))
                .thenReturn(new ProcessPendingNarrationMediaCleanupResult(0, 0, 0, Instant.now()));

        scheduler.cleanupPendingNarrationMedia();

        verify(cleanupUseCase).execute(50);
    }

    @Test
    @DisplayName("3. Catches and logs batch-level failure without rethrowing to protect the scheduler")
    void shouldHandleUnexpectedBatchFailureGracefully() {
        NarrationMediaCleanupScheduler scheduler =
                new NarrationMediaCleanupScheduler(cleanupUseCase, 50);

        when(cleanupUseCase.execute(anyInt()))
                .thenThrow(new RuntimeException("Database connection timeout during batch candidate lookup"));

        assertThatCode(scheduler::cleanupPendingNarrationMedia)
                .doesNotThrowAnyException();

        verify(cleanupUseCase).execute(50);
    }

    @Test
    @DisplayName("4. Rejects zero or negative configured batch size with IllegalArgumentException")
    void shouldRejectZeroOrNegativeConfiguredBatchSize() {
        assertThatThrownBy(() -> new NarrationMediaCleanupScheduler(cleanupUseCase, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");

        assertThatThrownBy(() -> new NarrationMediaCleanupScheduler(cleanupUseCase, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");
    }

    @Test
    @DisplayName("5. Rejects zero or negative scheduling fixed delay with IllegalArgumentException")
    void shouldRejectZeroOrNegativeConfiguredFixedDelay() {
        assertThatThrownBy(() -> new NarrationMediaCleanupScheduler(cleanupUseCase, 50, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scheduling fixed delay must be positive");

        assertThatThrownBy(() -> new NarrationMediaCleanupScheduler(cleanupUseCase, 50, -5000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scheduling fixed delay must be positive");
    }

    @Test
    @DisplayName("6. Rejects null cleanup use case with NullPointerException")
    void shouldRejectNullCleanupUseCase() {
        assertThatThrownBy(() -> new NarrationMediaCleanupScheduler(null, 50))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ProcessPendingNarrationMediaCleanupUseCase must not be null");
    }
}
