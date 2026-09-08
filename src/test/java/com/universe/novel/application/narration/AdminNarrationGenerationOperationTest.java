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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MS-04.9H.7D4A — Admin Narration Generation Operation Foundation Tests")
class AdminNarrationGenerationOperationTest {

    @Mock
    private GenerateChapterNarrationUseCase generateChapterNarrationUseCase;

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
        worker = new AdminNarrationGenerationWorker(generateChapterNarrationUseCase);
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
        verify(generateChapterNarrationUseCase, times(1)).execute(chapterId1, voiceId1);

        AdminNarrationOperationState finalState = syncDispatcher.getOperationState(chapterId1, voiceId1);
        assertThat(finalState.status()).isEqualTo(AdminNarrationOperationStatus.SUCCEEDED);
        assertThat(finalState.startedAt()).isNotNull();
        assertThat(finalState.completedAt()).isNotNull();
        assertThat(finalState.message()).contains("Hoàn tất tạo giọng đọc");
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
            Thread.sleep(100);

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
    @DisplayName("11. Partial outcome maps correctly to PARTIAL status")
    void partialOutcomeMapsToPartialStatus() {
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
        assertThat(finalState.status()).isEqualTo(AdminNarrationOperationStatus.PARTIAL);
        assertThat(finalState.message()).contains("1/2 đoạn thành công");
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
