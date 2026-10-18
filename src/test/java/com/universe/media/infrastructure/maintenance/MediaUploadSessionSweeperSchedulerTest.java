package com.universe.media.infrastructure.maintenance;

import com.universe.media.application.storage.SweepAbandonedMultipartUploadsCommand;
import com.universe.media.application.storage.SweepAbandonedMultipartUploadsResult;
import com.universe.media.application.storage.SweepAbandonedMultipartUploadsUseCase;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaUploadSessionSweeperScheduler Tests (MS-08E2-L3-2)")
class MediaUploadSessionSweeperSchedulerTest {

    @Mock
    private SweepAbandonedMultipartUploadsUseCase sweeperUseCase;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger schedulerLogger;

    @BeforeEach
    void setUp() {
        schedulerLogger = (Logger) LoggerFactory.getLogger(MediaUploadSessionSweeperScheduler.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        schedulerLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        if (schedulerLogger != null && listAppender != null) {
            schedulerLogger.detachAppender(listAppender);
        }
    }

    @Test
    @DisplayName("Scheduled sweep passes configured batchSize, maxRetries, and retryBackoff to use case and logs accurate counters")
    void shouldDelegateToUseCaseWithConfiguredProperties() {
        MediaUploadSessionSweeperScheduler scheduler = new MediaUploadSessionSweeperScheduler(
                sweeperUseCase,
                25,
                5,
                1_800_000L
        );

        when(sweeperUseCase.execute(any(SweepAbandonedMultipartUploadsCommand.class)))
                .thenReturn(new SweepAbandonedMultipartUploadsResult(10, 8, 2, 8, 0, 0, 0, Instant.now()));

        scheduler.sweepAbandonedMultipartUploads();

        ArgumentCaptor<SweepAbandonedMultipartUploadsCommand> captor =
                ArgumentCaptor.forClass(SweepAbandonedMultipartUploadsCommand.class);
        verify(sweeperUseCase).execute(captor.capture());

        SweepAbandonedMultipartUploadsCommand command = captor.getValue();
        assertThat(command.batchSize()).isEqualTo(25);
        assertThat(command.maxRetryCount()).isEqualTo(5);
        assertThat(command.retryBackoff()).isEqualTo(Duration.ofMinutes(30));

        // Verify scheduler logs accurately display updated counters
        List<ILoggingEvent> logs = listAppender.list;
        boolean foundCompletionLog = logs.stream()
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains("Completed scheduled sweeper for abandoned multipart upload sessions.")
                        && event.getFormattedMessage().contains("Scanned: 10, Attempted: 8, Skipped: 2, Quarantined: 8, VerifiedCleaned: 0, Failed: 0, ExpirationFailed: 0"));
        assertThat(foundCompletionLog).isTrue();
    }

    @Test
    @DisplayName("Unexpected exception in use case is safely caught and does not escape the scheduled method")
    void shouldCatchAndIsolateUnexpectedBatchFailure() {
        MediaUploadSessionSweeperScheduler scheduler = new MediaUploadSessionSweeperScheduler(
                sweeperUseCase,
                50,
                3,
                3_600_000L
        );

        when(sweeperUseCase.execute(any(SweepAbandonedMultipartUploadsCommand.class)))
                .thenThrow(new RuntimeException("Database connection timeout during batch sweep"));

        assertThatCode(scheduler::sweepAbandonedMultipartUploads)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Constructor rejects non-positive batchSize, negative maxRetries, or negative retryBackoffMs")
    void shouldRejectInvalidConstructorArguments() {
        assertThatThrownBy(() -> new MediaUploadSessionSweeperScheduler(sweeperUseCase, 0, 3, 3_600_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");

        assertThatThrownBy(() -> new MediaUploadSessionSweeperScheduler(sweeperUseCase, 50, -1, 3_600_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max retries cannot be negative");

        assertThatThrownBy(() -> new MediaUploadSessionSweeperScheduler(sweeperUseCase, 50, 3, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Retry backoff ms cannot be negative");
    }
}
