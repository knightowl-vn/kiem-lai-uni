package com.universe.novel.application.narration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReaderNarrationContinuationDispatcher Unit Tests (MS-04.9H.7C2C3B)")
class ReaderNarrationContinuationDispatcherTest {

    @Mock
    private TaskExecutor taskExecutor;
    @Mock
    private ReaderNarrationContinuationWorker worker;

    private ReaderNarrationContinuationDispatcher dispatcher;

    private static final UUID CHAPTER_1 = UUID.randomUUID();
    private static final UUID CHAPTER_2 = UUID.randomUUID();
    private static final UUID VOICE_1 = UUID.randomUUID();
    private static final UUID VOICE_2 = UUID.randomUUID();
    private static final UUID SEGMENT_1 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = new ReaderNarrationContinuationDispatcher(taskExecutor, worker);
    }

    @Test
    @DisplayName("Rejects null command")
    void rejectsNullCommand() {
        assertThatThrownBy(() -> dispatcher.dispatch(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("1, 5. Dispatch submits task to executor and returns SCHEDULED without waiting for completion")
    void dispatchSubmitsTaskAndReturnsScheduledImmediately() {
        ReaderNarrationContinuationCommand command = new ReaderNarrationContinuationCommand(
                CHAPTER_1, SEGMENT_1, VOICE_1, false
        );

        ReaderNarrationContinuationDispatchStatus status = dispatcher.dispatch(command);

        assertThat(status).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(captor.capture());

        // Worker was not called synchronously during dispatch
        verify(worker, never()).runContinuation(any());

        // When task runs asynchronously:
        captor.getValue().run();
        verify(worker).runContinuation(command);
    }

    @Test
    @DisplayName("6. Duplicate same (chapterId, managedVoiceId) while in-flight -> ALREADY_IN_FLIGHT, no duplicate submission")
    void duplicateSameChapterAndVoiceReturnsAlreadyInFlight() {
        ReaderNarrationContinuationCommand command1 = new ReaderNarrationContinuationCommand(
                CHAPTER_1, SEGMENT_1, VOICE_1, false
        );
        ReaderNarrationContinuationCommand command2 = new ReaderNarrationContinuationCommand(
                CHAPTER_1, UUID.randomUUID(), VOICE_1, true
        );

        // 1st dispatch succeeds and holds in-flight state (simulate asynchronous task not completed yet)
        ReaderNarrationContinuationDispatchStatus status1 = dispatcher.dispatch(command1);
        assertThat(status1).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);

        // 2nd dispatch with same chapter + voice while 1st is still in flight
        ReaderNarrationContinuationDispatchStatus status2 = dispatcher.dispatch(command2);
        assertThat(status2).isEqualTo(ReaderNarrationContinuationDispatchStatus.ALREADY_IN_FLIGHT);

        // Executor was only invoked once
        verify(taskExecutor, times(1)).execute(any());
    }

    @Test
    @DisplayName("7. Different chapter or voice key can schedule independently")
    void differentKeysCanScheduleIndependently() {
        ReaderNarrationContinuationCommand cmd1 = new ReaderNarrationContinuationCommand(CHAPTER_1, SEGMENT_1, VOICE_1, false);
        ReaderNarrationContinuationCommand cmd2 = new ReaderNarrationContinuationCommand(CHAPTER_2, SEGMENT_1, VOICE_1, false);
        ReaderNarrationContinuationCommand cmd3 = new ReaderNarrationContinuationCommand(CHAPTER_1, SEGMENT_1, VOICE_2, false);

        assertThat(dispatcher.dispatch(cmd1)).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);
        assertThat(dispatcher.dispatch(cmd2)).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);
        assertThat(dispatcher.dispatch(cmd3)).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);

        verify(taskExecutor, times(3)).execute(any());
    }

    @Test
    @DisplayName("8, 9. Executor rejection -> REJECTED, in-flight key released, work not run on caller thread")
    void executorRejectionReturnsRejectedAndReleasesKey() {
        ReaderNarrationContinuationCommand command = new ReaderNarrationContinuationCommand(
                CHAPTER_1, SEGMENT_1, VOICE_1, false
        );

        doThrow(new TaskRejectedException("Queue full"))
                .when(taskExecutor).execute(any(Runnable.class));

        ReaderNarrationContinuationDispatchStatus status = dispatcher.dispatch(command);

        assertThat(status).isEqualTo(ReaderNarrationContinuationDispatchStatus.REJECTED);
        verify(worker, never()).runContinuation(any());

        // In-flight key must be released so a subsequent attempt is not blocked as in-flight
        // Reset mock to accept
        doAnswer(invocation -> null).when(taskExecutor).execute(any(Runnable.class));
        ReaderNarrationContinuationDispatchStatus retryStatus = dispatcher.dispatch(command);
        assertThat(retryStatus).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);
    }

    @Test
    @DisplayName("10. In-flight key is released in finally even if worker throws RuntimeException")
    void inFlightKeyReleasedWhenWorkerThrowsException() {
        ReaderNarrationContinuationCommand command = new ReaderNarrationContinuationCommand(
                CHAPTER_1, SEGMENT_1, VOICE_1, false
        );

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        dispatcher.dispatch(command);
        verify(taskExecutor).execute(captor.capture());

        // Mock worker failure
        doThrow(new RuntimeException("Worker failure")).when(worker).runContinuation(command);

        assertThatThrownBy(() -> captor.getValue().run())
                .isInstanceOf(RuntimeException.class);

        // Subsequent dispatch for same chapter + voice should now be allowed (SCHEDULED, not ALREADY_IN_FLIGHT)
        ReaderNarrationContinuationDispatchStatus nextStatus = dispatcher.dispatch(command);
        assertThat(nextStatus).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);
    }
}
