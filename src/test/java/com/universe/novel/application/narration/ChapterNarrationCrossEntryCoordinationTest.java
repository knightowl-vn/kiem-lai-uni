package com.universe.novel.application.narration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChapterNarration Cross-Entry Coordination Tests (H.10A1)")
class ChapterNarrationCrossEntryCoordinationTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private AdminNarrationGenerationWorker adminWorker;

    @Mock
    private ReaderChapterNarrationPreparationWorker readerWorker;

    private ChapterNarrationExecutionCoordinator sharedCoordinator;

    @BeforeEach
    void setUp() {
        sharedCoordinator = new ChapterNarrationExecutionCoordinator();
    }

    @Test
    @DisplayName("1. Admin running -> Reader dispatch for same key is coalesced as ALREADY_IN_FLIGHT without launching worker")
    void adminRunningCausesReaderCoalescing() throws Exception {
        CountDownLatch adminStarted = new CountDownLatch(1);
        CountDownLatch adminProceed = new CountDownLatch(1);

        when(adminWorker.runGeneration(any(), any(), any())).thenAnswer(inv -> {
            adminStarted.countDown();
            adminProceed.await(5, TimeUnit.SECONDS);
            return AdminNarrationOperationState.succeeded(CHAPTER_ID, VOICE_ID, Instant.now(), Instant.now(), "Done");
        });

        ExecutorService threadPool = Executors.newFixedThreadPool(2);
        try {
            AdminNarrationGenerationDispatcher adminDispatcher = new AdminNarrationGenerationDispatcher(
                    threadPool::execute, adminWorker, sharedCoordinator
            );
            ReaderChapterNarrationPreparationDispatcher readerDispatcher = new ReaderChapterNarrationPreparationDispatcher(
                    threadPool::execute, readerWorker, sharedCoordinator
            );

            // 1. Admin dispatches first
            AdminNarrationDispatchResult adminResult = adminDispatcher.dispatch(CHAPTER_ID, VOICE_ID);
            assertThat(adminResult.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
            assertThat(adminStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // 2. Shared coordinator reflects in-flight
            assertThat(sharedCoordinator.isInFlight(CHAPTER_ID, VOICE_ID)).isTrue();

            // 3. Reader dispatches same chapter + voice while Admin is running
            ReaderChapterNarrationPreparationDispatchStatus readerStatus = readerDispatcher.dispatch(
                    new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID)
            );
            assertThat(readerStatus).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.ALREADY_IN_FLIGHT);

            // 4. Reader worker was NEVER invoked
            verify(readerWorker, never()).runPreparation(any());

            // Release Admin worker
            adminProceed.countDown();
            threadPool.shutdown();
            assertThat(threadPool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            threadPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("2. Reader running -> Admin dispatch for same key returns ALREADY_RUNNING and Admin UI reports RUNNING")
    void readerRunningCausesAdminAlreadyRunning() throws Exception {
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch readerProceed = new CountDownLatch(1);

        doAnswer(inv -> {
            readerStarted.countDown();
            readerProceed.await(5, TimeUnit.SECONDS);
            return null;
        }).when(readerWorker).runPreparation(any());

        ExecutorService threadPool = Executors.newFixedThreadPool(2);
        try {
            AdminNarrationGenerationDispatcher adminDispatcher = new AdminNarrationGenerationDispatcher(
                    threadPool::execute, adminWorker, sharedCoordinator
            );
            ReaderChapterNarrationPreparationDispatcher readerDispatcher = new ReaderChapterNarrationPreparationDispatcher(
                    threadPool::execute, readerWorker, sharedCoordinator
            );

            // 1. Reader dispatches first
            ReaderChapterNarrationPreparationDispatchStatus readerStatus = readerDispatcher.dispatch(
                    new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID)
            );
            assertThat(readerStatus).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
            assertThat(readerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // 2. Admin UI queries isRunning and operationState while Reader task runs
            assertThat(adminDispatcher.isRunning(CHAPTER_ID, VOICE_ID)).isTrue();
            AdminNarrationOperationState opState = adminDispatcher.getOperationState(CHAPTER_ID, VOICE_ID);
            assertThat(opState.status()).isEqualTo(AdminNarrationOperationStatus.RUNNING);

            // 3. Admin dispatches same chapter + voice while Reader task is in-flight
            AdminNarrationDispatchResult adminResult = adminDispatcher.dispatch(CHAPTER_ID, VOICE_ID);
            assertThat(adminResult.status()).isEqualTo(AdminNarrationDispatchStatus.ALREADY_RUNNING);
            assertThat(adminResult.state().status()).isEqualTo(AdminNarrationOperationStatus.RUNNING);

            // 4. Admin worker was NEVER invoked
            verify(adminWorker, never()).runGeneration(any(), any(), any());

            // Release Reader worker
            readerProceed.countDown();
            threadPool.shutdown();
            assertThat(threadPool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            threadPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("3. Successful Admin task completion releases shared key for subsequent Reader execution")
    void adminSuccessReleasesSharedKey() {
        when(adminWorker.runGeneration(any(), any(), any())).thenReturn(
                AdminNarrationOperationState.succeeded(CHAPTER_ID, VOICE_ID, Instant.now(), Instant.now(), "Succeeded")
        );

        AdminNarrationGenerationDispatcher adminDispatcher = new AdminNarrationGenerationDispatcher(
                new SyncTaskExecutor(), adminWorker, sharedCoordinator
        );
        ReaderChapterNarrationPreparationDispatcher readerDispatcher = new ReaderChapterNarrationPreparationDispatcher(
                new SyncTaskExecutor(), readerWorker, sharedCoordinator
        );

        // Admin runs to completion
        AdminNarrationDispatchResult adminResult = adminDispatcher.dispatch(CHAPTER_ID, VOICE_ID);
        assertThat(adminResult.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
        assertThat(sharedCoordinator.isInFlight(CHAPTER_ID, VOICE_ID)).isFalse();

        // Reader can now schedule
        ReaderChapterNarrationPreparationDispatchStatus readerStatus = readerDispatcher.dispatch(
                new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID)
        );
        assertThat(readerStatus).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
        verify(readerWorker).runPreparation(new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID));
    }

    @Test
    @DisplayName("4. Admin worker RuntimeException still releases shared key")
    void adminWorkerFailureReleasesSharedKey() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        when(adminWorker.runGeneration(any(), any(), any())).thenAnswer(inv -> {
            try {
                throw new RuntimeException("TTS failure");
            } finally {
                latch.countDown();
            }
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AdminNarrationGenerationDispatcher adminDispatcher = new AdminNarrationGenerationDispatcher(
                    pool::execute, adminWorker, sharedCoordinator
            );

            AdminNarrationDispatchResult result = adminDispatcher.dispatch(CHAPTER_ID, VOICE_ID);
            assertThat(result.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();

            // Key is cleanly released in finally block
            assertThat(sharedCoordinator.isInFlight(CHAPTER_ID, VOICE_ID)).isFalse();
            assertThat(adminDispatcher.isRunning(CHAPTER_ID, VOICE_ID)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("5. Reader worker RuntimeException still releases shared key")
    void readerWorkerFailureReleasesSharedKey() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            try {
                throw new RuntimeException("Assembler error");
            } finally {
                latch.countDown();
            }
        }).when(readerWorker).runPreparation(any());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ReaderChapterNarrationPreparationDispatcher readerDispatcher = new ReaderChapterNarrationPreparationDispatcher(
                    pool::execute, readerWorker, sharedCoordinator
            );

            ReaderChapterNarrationPreparationDispatchStatus status = readerDispatcher.dispatch(
                    new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID)
            );
            assertThat(status).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();

            // Key is cleanly released in finally block
            assertThat(sharedCoordinator.isInFlight(CHAPTER_ID, VOICE_ID)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("6. Admin executor rejection releases shared key")
    void adminExecutorRejectionReleasesSharedKey() {
        TaskExecutor rejectingExecutor = mock(TaskExecutor.class);
        doThrow(new RejectedExecutionException("Full")).when(rejectingExecutor).execute(any());

        AdminNarrationGenerationDispatcher adminDispatcher = new AdminNarrationGenerationDispatcher(
                rejectingExecutor, adminWorker, sharedCoordinator
        );

        AdminNarrationDispatchResult result = adminDispatcher.dispatch(CHAPTER_ID, VOICE_ID);
        assertThat(result.status()).isEqualTo(AdminNarrationDispatchStatus.REJECTED);

        assertThat(sharedCoordinator.isInFlight(CHAPTER_ID, VOICE_ID)).isFalse();
    }

    @Test
    @DisplayName("7. Reader executor rejection releases shared key")
    void readerExecutorRejectionReleasesSharedKey() {
        TaskExecutor rejectingExecutor = mock(TaskExecutor.class);
        doThrow(new RejectedExecutionException("Full")).when(rejectingExecutor).execute(any());

        ReaderChapterNarrationPreparationDispatcher readerDispatcher = new ReaderChapterNarrationPreparationDispatcher(
                rejectingExecutor, readerWorker, sharedCoordinator
        );

        ReaderChapterNarrationPreparationDispatchStatus status = readerDispatcher.dispatch(
                new ReaderChapterNarrationPreparationCommand(CHAPTER_ID, VOICE_ID)
        );
        assertThat(status).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.REJECTED);

        assertThat(sharedCoordinator.isInFlight(CHAPTER_ID, VOICE_ID)).isFalse();
    }
}
