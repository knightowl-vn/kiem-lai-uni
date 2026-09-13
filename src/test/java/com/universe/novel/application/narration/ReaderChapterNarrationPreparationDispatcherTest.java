package com.universe.novel.application.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReaderChapterNarrationPreparationDispatcher Unit Tests (MS-04.9H.9, H.9I5B)")
class ReaderChapterNarrationPreparationDispatcherTest {

    private static final UUID CHAPTER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VOICE_2 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private ReaderChapterNarrationPreparationWorker worker;

    @Test
    @DisplayName("1. First request for a chapter+voice pair returns SCHEDULED")
    void firstRequestReturnsScheduled() {
        ReaderChapterNarrationPreparationDispatcher dispatcher =
                new ReaderChapterNarrationPreparationDispatcher(new SyncTaskExecutor(), worker);

        ReaderChapterNarrationPreparationDispatchStatus status = dispatcher.dispatch(
                new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_1)
        );

        assertThat(status).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
        verify(worker).runPreparation(new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_1));
    }

    @Test
    @DisplayName("2. Duplicate in-flight request for the same chapter+voice returns ALREADY_IN_FLIGHT and does not invoke worker twice")
    void duplicateInFlightRequestReturnsAlreadyInFlight() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerProceed = new CountDownLatch(1);

        doAnswer(inv -> {
            workerStarted.countDown();
            workerProceed.await(5, TimeUnit.SECONDS);
            return null;
        }).when(worker).runPreparation(any());

        ExecutorService threadPool = Executors.newFixedThreadPool(2);
        try {
            ReaderChapterNarrationPreparationDispatcher dispatcher =
                    new ReaderChapterNarrationPreparationDispatcher(threadPool::execute, worker);

            ReaderChapterNarrationPreparationCommand cmd = new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_1);

            ReaderChapterNarrationPreparationDispatchStatus status1 = dispatcher.dispatch(cmd);
            assertThat(status1).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);

            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // Duplicate call while first is in-flight
            ReaderChapterNarrationPreparationDispatchStatus status2 = dispatcher.dispatch(cmd);
            assertThat(status2).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.ALREADY_IN_FLIGHT);

            // Release worker
            workerProceed.countDown();
        } finally {
            threadPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("3. Different chapter or voice keys schedule independently without blocking each other")
    void differentKeysScheduleIndependently() {
        ReaderChapterNarrationPreparationDispatcher dispatcher =
                new ReaderChapterNarrationPreparationDispatcher(new SyncTaskExecutor(), worker);

        ReaderChapterNarrationPreparationDispatchStatus status1 = dispatcher.dispatch(
                new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_1)
        );
        ReaderChapterNarrationPreparationDispatchStatus status2 = dispatcher.dispatch(
                new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_2)
        );
        ReaderChapterNarrationPreparationDispatchStatus status3 = dispatcher.dispatch(
                new ReaderChapterNarrationPreparationCommand(CHAPTER_B, VOICE_1)
        );

        assertThat(status1).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
        assertThat(status2).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
        assertThat(status3).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
    }

    @Test
    @DisplayName("4. Worker is not executed synchronously on the caller thread when using an async executor")
    void workerNotExecutedSynchronouslyOnCallerThread() throws Exception {
        CountDownLatch executed = new CountDownLatch(1);
        AtomicBoolean ranSynchronously = new AtomicBoolean(false);
        Thread callerThread = Thread.currentThread();

        doAnswer(inv -> {
            if (Thread.currentThread() == callerThread) {
                ranSynchronously.set(true);
            }
            executed.countDown();
            return null;
        }).when(worker).runPreparation(any());

        ExecutorService threadPool = Executors.newSingleThreadExecutor();
        try {
            ReaderChapterNarrationPreparationDispatcher dispatcher =
                    new ReaderChapterNarrationPreparationDispatcher(threadPool::execute, worker);

            ReaderChapterNarrationPreparationDispatchStatus status = dispatcher.dispatch(
                    new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_1)
            );

            assertThat(status).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
            assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(ranSynchronously.get()).isFalse();
        } finally {
            threadPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("5. Executor rejection returns REJECTED and immediately releases the in-flight key for future retry")
    void executorRejectionReturnsRejectedAndReleasesKey() {
        TaskExecutor rejectingExecutor = mock(TaskExecutor.class);
        doThrow(new RejectedExecutionException("Queue full")).when(rejectingExecutor).execute(any());

        ReaderChapterNarrationPreparationDispatcher dispatcher =
                new ReaderChapterNarrationPreparationDispatcher(rejectingExecutor, worker);

        ReaderChapterNarrationPreparationCommand cmd = new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_1);
        ReaderChapterNarrationPreparationDispatchStatus status = dispatcher.dispatch(cmd);

        assertThat(status).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.REJECTED);
        verify(worker, never()).runPreparation(any());

        // Subsequent submission for same key is NOT blocked by an unreleased key
        ReaderChapterNarrationPreparationDispatchStatus retryStatus = dispatcher.dispatch(cmd);
        assertThat(retryStatus).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.REJECTED);
    }

    @Test
    @DisplayName("6. Unexpected RuntimeException during dispatch returns REJECTED and releases the in-flight key")
    void unexpectedRuntimeExceptionReleasesKey() {
        TaskExecutor failingExecutor = mock(TaskExecutor.class);
        doThrow(new IllegalStateException("Executor unexpected error")).when(failingExecutor).execute(any());

        ReaderChapterNarrationPreparationDispatcher dispatcher =
                new ReaderChapterNarrationPreparationDispatcher(failingExecutor, worker);

        ReaderChapterNarrationPreparationCommand cmd = new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_1);
        ReaderChapterNarrationPreparationDispatchStatus status = dispatcher.dispatch(cmd);

        assertThat(status).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.REJECTED);
        verify(worker, never()).runPreparation(any());

        // Key is released
        ReaderChapterNarrationPreparationDispatchStatus retryStatus = dispatcher.dispatch(cmd);
        assertThat(retryStatus).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.REJECTED);
    }

    @Test
    @DisplayName("7. Worker completion releases in-flight key so same chapter+voice can be scheduled again later")
    void workerCompletionReleasesKey() {
        ReaderChapterNarrationPreparationDispatcher dispatcher =
                new ReaderChapterNarrationPreparationDispatcher(new SyncTaskExecutor(), worker);

        ReaderChapterNarrationPreparationCommand cmd = new ReaderChapterNarrationPreparationCommand(CHAPTER_A, VOICE_1);

        ReaderChapterNarrationPreparationDispatchStatus status1 = dispatcher.dispatch(cmd);
        assertThat(status1).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);

        // After completion of sync executor, key is released
        ReaderChapterNarrationPreparationDispatchStatus status2 = dispatcher.dispatch(cmd);
        assertThat(status2).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
    }

    @Test
    @DisplayName("8. Null command throws IllegalArgumentException")
    void nullCommandThrowsIllegalArgumentException() {
        ReaderChapterNarrationPreparationDispatcher dispatcher =
                new ReaderChapterNarrationPreparationDispatcher(new SyncTaskExecutor(), worker);

        assertThatThrownBy(() -> dispatcher.dispatch(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
