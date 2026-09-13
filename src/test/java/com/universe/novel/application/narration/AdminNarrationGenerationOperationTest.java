package com.universe.novel.application.narration;

import com.universe.novel.infrastructure.narration.config.AdminNarrationGenerationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MS-04.9H.7D4A — Admin Narration Generation Operation Foundation Tests")
class AdminNarrationGenerationOperationTest {

    @Mock
    private GenerateChapterNarrationUseCase generateChapterNarrationUseCase;

    @Mock
    private BuildChapterNarrationPlaybackUseCase buildChapterNarrationPlaybackUseCase;

    @Mock
    private TaskExecutor mockTaskExecutor;

    private AdminNarrationGenerationWorker worker;
    private AdminNarrationGenerationDispatcher syncDispatcher;

    private final UUID chapterId1 = UUID.randomUUID();
    private final UUID chapterId2 = UUID.randomUUID();
    private final UUID voiceId1 = UUID.randomUUID();
    private final UUID voiceId2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(buildChapterNarrationPlaybackUseCase.execute(any()))
                .thenReturn(new BuildChapterNarrationPlaybackResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        worker = new AdminNarrationGenerationWorker(generateChapterNarrationUseCase, buildChapterNarrationPlaybackUseCase);
        syncDispatcher = new AdminNarrationGenerationDispatcher(new SyncTaskExecutor(), worker);
    }

    @Test
    @DisplayName("1. First command starts generation and completes successfully")
    void firstCommandStartsGenerationAndCompletes() {
        GenerateChapterNarrationResult successResult = new GenerateChapterNarrationResult(
                chapterId1,
                voiceId1,
                List.of(
                        ChapterNarrationSegmentExecutionResult.skippedReady(UUID.randomUUID(), 0),
                        ChapterNarrationSegmentExecutionResult.success(
                                UUID.randomUUID(), 1, ChapterNarrationGenerationAction.GENERATE,
                                ChapterNarrationGenerationExecutionOutcome.GENERATED
                        )
                ),
                ChapterNarrationCompletionCleanupSummary.notEligible()
        );

        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1)).thenReturn(successResult);

        AdminNarrationDispatchResult dispatchResult = syncDispatcher.dispatch(chapterId1, voiceId1);

        assertThat(dispatchResult.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
        var order = inOrder(generateChapterNarrationUseCase, buildChapterNarrationPlaybackUseCase);
        order.verify(generateChapterNarrationUseCase).execute(chapterId1, voiceId1);
        order.verify(buildChapterNarrationPlaybackUseCase).execute(new BuildChapterNarrationPlaybackCommand(chapterId1, voiceId1));
        order.verifyNoMoreInteractions();

        AdminNarrationOperationState finalState = syncDispatcher.getOperationState(chapterId1, voiceId1);
        assertThat(finalState.status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
        assertThat(finalState.startedAt()).isNotNull();
        assertThat(finalState.completedAt()).isNotNull();
        assertThat(finalState.message()).contains("Hoàn tất tạo / cập nhật audio cả chương");
        assertThat(syncDispatcher.isRunning(chapterId1, voiceId1)).isFalse();
    }

    @Test
    @DisplayName("2. Duplicate dispatch for same (chapterId, voiceId) while running returns ALREADY_RUNNING")
    void duplicateDispatchWhileRunningReturnsAlreadyRunning() throws InterruptedException {
        CountDownLatch useCaseStarted = new CountDownLatch(1);
        CountDownLatch allowUseCaseToFinish = new CountDownLatch(1);

        doAnswer(invocation -> {
            useCaseStarted.countDown();
            allowUseCaseToFinish.await(5, TimeUnit.SECONDS);
            return new GenerateChapterNarrationResult(
                    chapterId1, voiceId1, Collections.emptyList(), ChapterNarrationCompletionCleanupSummary.notEligible()
            );
        }).when(generateChapterNarrationUseCase).execute(chapterId1, voiceId1);

        ExecutorService asyncPool = Executors.newFixedThreadPool(2);
        try {
            AdminNarrationGenerationDispatcher asyncDispatcher = new AdminNarrationGenerationDispatcher(
                    asyncPool::execute, worker
            );

            // First dispatch starts in background
            AdminNarrationDispatchResult firstResult = asyncDispatcher.dispatch(chapterId1, voiceId1);
            assertThat(firstResult.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);

            assertThat(useCaseStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(asyncDispatcher.isRunning(chapterId1, voiceId1)).isTrue();

            // Second dispatch for SAME key while running
            AdminNarrationDispatchResult secondResult = asyncDispatcher.dispatch(chapterId1, voiceId1);
            assertThat(secondResult.status()).isEqualTo(AdminNarrationDispatchStatus.ALREADY_RUNNING);
            assertThat(secondResult.state().status()).isEqualTo(AdminNarrationOperationStatus.RUNNING);

            // Allow first task to complete
            allowUseCaseToFinish.countDown();
            asyncPool.shutdown();
            assertThat(asyncPool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();

            assertThat(asyncDispatcher.isRunning(chapterId1, voiceId1)).isFalse();
            verify(generateChapterNarrationUseCase, times(1)).execute(chapterId1, voiceId1);
        } finally {
            asyncPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("3. Different chapter/voice keys execute independently")
    void differentKeysExecuteIndependently() {
        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1)).thenReturn(
                new GenerateChapterNarrationResult(chapterId1, voiceId1, Collections.emptyList())
        );
        when(generateChapterNarrationUseCase.execute(chapterId2, voiceId2)).thenReturn(
                new GenerateChapterNarrationResult(chapterId2, voiceId2, Collections.emptyList())
        );

        AdminNarrationDispatchResult r1 = syncDispatcher.dispatch(chapterId1, voiceId1);
        AdminNarrationDispatchResult r2 = syncDispatcher.dispatch(chapterId2, voiceId2);

        assertThat(r1.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
        assertThat(r2.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);

        verify(generateChapterNarrationUseCase, times(1)).execute(chapterId1, voiceId1);
        verify(generateChapterNarrationUseCase, times(1)).execute(chapterId2, voiceId2);
    }

    @Test
    @DisplayName("4. GenerateChapterNarrationUseCase is the sole generation authority")
    void generateChapterNarrationUseCaseIsSoleGenerationAuthority() {
        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1)).thenReturn(
                new GenerateChapterNarrationResult(chapterId1, voiceId1, Collections.emptyList())
        );

        syncDispatcher.dispatch(chapterId1, voiceId1);

        verify(generateChapterNarrationUseCase).execute(chapterId1, voiceId1);
    }

    @Test
    @DisplayName("5. Successful completion releases single-flight key for subsequent execution")
    void successReleasesSingleFlightKey() {
        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1)).thenReturn(
                new GenerateChapterNarrationResult(chapterId1, voiceId1, Collections.emptyList())
        );

        // Run 1
        AdminNarrationDispatchResult r1 = syncDispatcher.dispatch(chapterId1, voiceId1);
        assertThat(r1.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
        assertThat(syncDispatcher.isRunning(chapterId1, voiceId1)).isFalse();

        // Run 2 (after completion)
        AdminNarrationDispatchResult r2 = syncDispatcher.dispatch(chapterId1, voiceId1);
        assertThat(r2.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);

        verify(generateChapterNarrationUseCase, times(2)).execute(chapterId1, voiceId1);
    }

    @Test
    @DisplayName("6. RuntimeException releases key and produces safe FAILED state")
    void runtimeExceptionReleasesKeyAndProducesSafeFailedState() {
        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1))
                .thenThrow(new IllegalStateException("Internal database lock timeout or network glitch"));

        AdminNarrationDispatchResult result = syncDispatcher.dispatch(chapterId1, voiceId1);
        assertThat(result.status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);

        assertThat(syncDispatcher.isRunning(chapterId1, voiceId1)).isFalse();
        AdminNarrationOperationState finalState = syncDispatcher.getOperationState(chapterId1, voiceId1);
        assertThat(finalState.status()).isEqualTo(AdminNarrationOperationStatus.FAILED);
        assertThat(finalState.message()).isEqualTo(AdminNarrationGenerationWorker.SAFE_FAILURE_MESSAGE);
        assertThat(finalState.message()).doesNotContain("Internal database lock");
        assertThat(finalState.message()).doesNotContain("timeout");
        verifyNoInteractions(buildChapterNarrationPlaybackUseCase);
    }

    @Test
    @DisplayName("7. Executor rejection returns safe REJECTED start result and releases in-flight key")
    void executorRejectionReturnsSafeRejectedResultAndReleasesKey() {
        doThrow(new RejectedExecutionException("Queue capacity full"))
                .when(mockTaskExecutor).execute(any(Runnable.class));

        AdminNarrationGenerationDispatcher rejectingDispatcher =
                new AdminNarrationGenerationDispatcher(mockTaskExecutor, worker);

        AdminNarrationDispatchResult result = rejectingDispatcher.dispatch(chapterId1, voiceId1);

        assertThat(result.status()).isEqualTo(AdminNarrationDispatchStatus.REJECTED);
        assertThat(rejectingDispatcher.isRunning(chapterId1, voiceId1)).isFalse();
        assertThat(result.message()).contains("Hàng đợi tác vụ tạo giọng đọc đã đầy");

        AdminNarrationOperationState state = rejectingDispatcher.getOperationState(chapterId1, voiceId1);
        assertThat(state.status()).isEqualTo(AdminNarrationOperationStatus.FAILED);
        assertThat(state.message()).contains("Hàng đợi tác vụ đã đầy");
    }

    @Test
    @DisplayName("8. No raw exception text, provider IDs, or internal storage details exposed")
    void noRawExceptionOrProviderDetailsExposed() {
        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1))
                .thenThrow(new RuntimeException("Cloudinary API token leaked or S3 bucket permission denied"));

        syncDispatcher.dispatch(chapterId1, voiceId1);

        AdminNarrationOperationState state = syncDispatcher.getOperationState(chapterId1, voiceId1);
        assertThat(state.message()).doesNotContain("Cloudinary");
        assertThat(state.message()).doesNotContain("S3");
        assertThat(state.message()).doesNotContain("token");
        assertThat(state.message()).doesNotContain("permission denied");
    }

    @Test
    @DisplayName("9. Worker and Dispatcher have zero direct Media/TTS dependencies")
    void noDirectMediaOrTtsDependencies() {
        for (Method method : AdminNarrationGenerationWorker.class.getDeclaredMethods()) {
            for (Class<?> paramType : method.getParameterTypes()) {
                assertThat(paramType.getName()).doesNotContain("tts");
                assertThat(paramType.getName()).doesNotContain("media");
            }
        }
        for (Method method : AdminNarrationGenerationDispatcher.class.getDeclaredMethods()) {
            for (Class<?> paramType : method.getParameterTypes()) {
                assertThat(paramType.getName()).doesNotContain("tts");
                assertThat(paramType.getName()).doesNotContain("media");
            }
        }
    }

    @Test
    @DisplayName("10. No outer transaction around long background generation")
    void noOuterTransactionAroundBackgroundGeneration() {
        assertThat(AdminNarrationGenerationWorker.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AdminNarrationGenerationDispatcher.class.isAnnotationPresent(Transactional.class)).isFalse();

        for (Method method : AdminNarrationGenerationWorker.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        }
        for (Method method : AdminNarrationGenerationDispatcher.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        }
    }

    @Test
    @DisplayName("11. Partial readiness fails the operation without building chapter playback")
    void partialReadinessFailsWithoutBuildingPlayback() {
        GenerateChapterNarrationResult partialResult = new GenerateChapterNarrationResult(
                chapterId1,
                voiceId1,
                List.of(
                        ChapterNarrationSegmentExecutionResult.skippedReady(UUID.randomUUID(), 0),
                        ChapterNarrationSegmentExecutionResult.failure(
                                UUID.randomUUID(), 1, ChapterNarrationGenerationAction.GENERATE,
                                "TTS_ERROR", "Failed"
                        )
                ),
                ChapterNarrationCompletionCleanupSummary.notEligible()
        );

        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1)).thenReturn(partialResult);

        syncDispatcher.dispatch(chapterId1, voiceId1);

        AdminNarrationOperationState finalState = syncDispatcher.getOperationState(chapterId1, voiceId1);
        assertThat(finalState.status()).isEqualTo(AdminNarrationOperationStatus.FAILED);
        assertThat(finalState.message()).isEqualTo(AdminNarrationGenerationWorker.SAFE_FAILURE_MESSAGE);
        assertThat(syncDispatcher.isRunning(chapterId1, voiceId1)).isFalse();
        verifyNoInteractions(buildChapterNarrationPlaybackUseCase);
    }

    @Test
    void legacyAllReadyResultStillBuildsChapterPlayback() {
        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1)).thenReturn(allReady(chapterId1, voiceId1));

        syncDispatcher.dispatch(chapterId1, voiceId1);

        var order = inOrder(generateChapterNarrationUseCase, buildChapterNarrationPlaybackUseCase);
        order.verify(generateChapterNarrationUseCase).execute(chapterId1, voiceId1);
        order.verify(buildChapterNarrationPlaybackUseCase).execute(new BuildChapterNarrationPlaybackCommand(chapterId1, voiceId1));
        order.verifyNoMoreInteractions();
        assertThat(syncDispatcher.getOperationState(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
        assertThat(syncDispatcher.getOperationState(chapterId1, voiceId1).message())
                .contains("đã có: 3, tạo mới: 0, cập nhật: 0");
    }

    @Test
    void builderFailureIsSafeAndReleasesKeyForRetry() {
        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1)).thenReturn(allReady(chapterId1, voiceId1));
        BuildChapterNarrationPlaybackCommand command = new BuildChapterNarrationPlaybackCommand(chapterId1, voiceId1);
        when(buildChapterNarrationPlaybackUseCase.execute(command))
                .thenThrow(new IllegalStateException("FFmpeg C:/private/audio.wav storage-key Media provider-voice-id"))
                .thenReturn(new BuildChapterNarrationPlaybackResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        syncDispatcher.dispatch(chapterId1, voiceId1);

        AdminNarrationOperationState failed = syncDispatcher.getOperationState(chapterId1, voiceId1);
        assertThat(failed.status()).isEqualTo(AdminNarrationOperationStatus.FAILED);
        assertThat(failed.message()).isEqualTo(AdminNarrationGenerationWorker.SAFE_FAILURE_MESSAGE);
        assertThat(failed.completedAt()).isNotNull();
        assertThat(syncDispatcher.isRunning(chapterId1, voiceId1)).isFalse();

        assertThat(syncDispatcher.dispatch(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
        assertThat(syncDispatcher.getOperationState(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
        verify(buildChapterNarrationPlaybackUseCase, times(2)).execute(command);
    }

    @Test
    void failedOrRetryRequiredReadinessNeverBuildsPlayback() {
        for (ChapterNarrationSegmentExecutionResult item : List.of(
                ChapterNarrationSegmentExecutionResult.failure(UUID.randomUUID(), 0,
                        ChapterNarrationGenerationAction.GENERATE, "TTS_ERROR", "private provider details"),
                ChapterNarrationSegmentExecutionResult.success(UUID.randomUUID(), 0,
                        ChapterNarrationGenerationAction.GENERATE, ChapterNarrationGenerationExecutionOutcome.RETRY_REQUIRED)
        )) {
            when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1))
                    .thenReturn(new GenerateChapterNarrationResult(chapterId1, voiceId1, List.of(item)));
            assertThat(syncDispatcher.dispatch(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
            assertThat(syncDispatcher.getOperationState(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationOperationStatus.FAILED);
            assertThat(syncDispatcher.getOperationState(chapterId1, voiceId1).message())
                    .isEqualTo(AdminNarrationGenerationWorker.SAFE_FAILURE_MESSAGE);
            assertThat(syncDispatcher.isRunning(chapterId1, voiceId1)).isFalse();
        }
        verifyNoInteractions(buildChapterNarrationPlaybackUseCase);
    }

    @Test
    void singleFlightAndRunningStateSpanBuilderWhileOtherKeysComplete() throws InterruptedException {
        CountDownLatch builderStarted = new CountDownLatch(1);
        CountDownLatch allowBuilderToFinish = new CountDownLatch(1);
        CountDownLatch independentTasksFinished = new CountDownLatch(2);
        ExecutorService asyncPool = Executors.newFixedThreadPool(3);
        when(generateChapterNarrationUseCase.execute(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> allReady(invocation.getArgument(0), invocation.getArgument(1)));
        BuildChapterNarrationPlaybackCommand blockedCommand = new BuildChapterNarrationPlaybackCommand(chapterId1, voiceId1);
        when(buildChapterNarrationPlaybackUseCase.execute(blockedCommand)).thenAnswer(invocation -> {
            builderStarted.countDown();
            assertThat(allowBuilderToFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return new BuildChapterNarrationPlaybackResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        });
        AdminNarrationGenerationDispatcher dispatcher = new AdminNarrationGenerationDispatcher(
                task -> asyncPool.execute(() -> {
                    task.run();
                    independentTasksFinished.countDown();
                }), worker);
        try {
            assertThat(dispatcher.dispatch(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
            assertThat(builderStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.isRunning(chapterId1, voiceId1)).isTrue();
            assertThat(dispatcher.getOperationState(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationOperationStatus.RUNNING);
            assertThat(dispatcher.getOperationState(chapterId1, voiceId1).completedAt()).isNull();
            assertThat(dispatcher.dispatch(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationDispatchStatus.ALREADY_RUNNING);

            // Same chapter with another voice, and another chapter with the same voice, remain independent.
            assertThat(dispatcher.dispatch(chapterId1, voiceId2).status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
            assertThat(dispatcher.dispatch(chapterId2, voiceId1).status()).isEqualTo(AdminNarrationDispatchStatus.STARTED);
            assertThat(independentTasksFinished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.getOperationState(chapterId1, voiceId2).status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
            assertThat(dispatcher.getOperationState(chapterId2, voiceId1).status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
            assertThat(dispatcher.getOperationState(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationOperationStatus.RUNNING);

            allowBuilderToFinish.countDown();
            asyncPool.shutdown();
            assertThat(asyncPool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.getOperationState(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
            assertThat(dispatcher.isRunning(chapterId1, voiceId1)).isFalse();
            verify(generateChapterNarrationUseCase).execute(chapterId1, voiceId1);
            verify(buildChapterNarrationPlaybackUseCase).execute(blockedCommand);
            verify(buildChapterNarrationPlaybackUseCase).execute(new BuildChapterNarrationPlaybackCommand(chapterId1, voiceId2));
            verify(buildChapterNarrationPlaybackUseCase).execute(new BuildChapterNarrationPlaybackCommand(chapterId2, voiceId1));
        } finally {
            allowBuilderToFinish.countDown();
            asyncPool.shutdownNow();
        }
    }

    @Test
    void alreadyCurrentSucceedsWithSafeNoChangeMessageAndReleasesKey() {
        when(generateChapterNarrationUseCase.execute(chapterId1, voiceId1)).thenReturn(allReady(chapterId1, voiceId1));
        when(buildChapterNarrationPlaybackUseCase.execute(any())).thenReturn(new BuildChapterNarrationPlaybackResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BuildChapterNarrationPlaybackOutcome.ALREADY_CURRENT));
        syncDispatcher.dispatch(chapterId1, voiceId1);
        assertThat(syncDispatcher.getOperationState(chapterId1, voiceId1).status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
        assertThat(syncDispatcher.getOperationState(chapterId1, voiceId1).message())
                .isEqualTo(AdminNarrationGenerationWorker.SAFE_ALREADY_CURRENT_MESSAGE);
        assertThat(syncDispatcher.isRunning(chapterId1, voiceId1)).isFalse();
    }

    private GenerateChapterNarrationResult allReady(UUID chapterId, UUID voiceId) {
        return new GenerateChapterNarrationResult(chapterId, voiceId, List.of(
                ChapterNarrationSegmentExecutionResult.skippedReady(UUID.randomUUID(), 0),
                ChapterNarrationSegmentExecutionResult.skippedReady(UUID.randomUUID(), 1),
                ChapterNarrationSegmentExecutionResult.skippedReady(UUID.randomUUID(), 2)
        ));
    }

    @Test
    @DisplayName("12. AdminNarrationGenerationConfig configures dedicated bounded executor with AbortPolicy")
    void configConfiguresDedicatedBoundedExecutorWithAbortPolicy() {
        AdminNarrationGenerationConfig config = new AdminNarrationGenerationConfig();
        TaskExecutor executor = config.adminNarrationGenerationTaskExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor threadPool = (ThreadPoolTaskExecutor) executor;

        assertThat(threadPool.getCorePoolSize()).isEqualTo(2);
        assertThat(threadPool.getMaxPoolSize()).isEqualTo(4);
        assertThat(threadPool.getQueueCapacity()).isEqualTo(20);
        assertThat(threadPool.getThreadNamePrefix()).isEqualTo("admin-narration-gen-");

        ThreadPoolExecutor rawExecutor = threadPool.getThreadPoolExecutor();
        assertThat(rawExecutor.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

        threadPool.destroy();
    }
}
