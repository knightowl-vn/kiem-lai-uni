package com.universe.novel.application.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Dispatcher and lifecycle coordinator for Admin-initiated whole-chapter Managed narration generation (MS-04.9H.7D4A, H.10A1).
 * <p>
 * <strong>Single-Flight Concurrency Control:</strong>
 * Delegates single-flight ownership to the shared {@link ChapterNarrationExecutionCoordinator} keyed by {@code (chapterId, managedVoiceId)}.
 * If generation or preparation is actively running or queued for the same key across Admin or Reader domains,
 * subsequent requests return {@link AdminNarrationDispatchStatus#ALREADY_RUNNING}.
 * <p>
 * <strong>Rejection & Thread Safety:</strong>
 * Uses shared bounded {@code chapterNarrationExecutionTaskExecutor}. Task rejection immediately releases the in-flight key
 * and records a {@link AdminNarrationOperationStatus#FAILED} state with a safe message. Work is NEVER executed on the caller thread.
 */
@Component
public class AdminNarrationGenerationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AdminNarrationGenerationDispatcher.class);

    private record OperationKey(UUID chapterId, UUID managedVoiceId) {
        private OperationKey {
            Objects.requireNonNull(chapterId, "chapterId must not be null");
            Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        }
    }

    private final TaskExecutor taskExecutor;
    private final AdminNarrationGenerationWorker worker;
    private final ChapterNarrationExecutionCoordinator coordinator;
    private final ConcurrentMap<OperationKey, AdminNarrationOperationState> operationStates = new ConcurrentHashMap<>();

    public AdminNarrationGenerationDispatcher(
            @Qualifier("chapterNarrationExecutionTaskExecutor") TaskExecutor taskExecutor,
            AdminNarrationGenerationWorker worker,
            ChapterNarrationExecutionCoordinator coordinator
    ) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
    }

    /**
     * Submits a whole-chapter narration generation request for asynchronous background execution.
     *
     * @param chapterId      identity of the chapter (non-null)
     * @param managedVoiceId identity of the managed voice (non-null)
     * @return dispatch result containing disposition and operation snapshot
     */
    public AdminNarrationDispatchResult dispatch(UUID chapterId, UUID managedVoiceId) {
        if (chapterId == null || managedVoiceId == null) {
            UUID safeChapterId = chapterId != null ? chapterId : new UUID(0L, 0L);
            UUID safeVoiceId = managedVoiceId != null ? managedVoiceId : new UUID(0L, 0L);
            AdminNarrationOperationState errState = AdminNarrationOperationState.failed(
                    safeChapterId, safeVoiceId, Instant.now(), Instant.now(), "chapterId và managedVoiceId không được để trống."
            );
            return AdminNarrationDispatchResult.failed(errState, "chapterId và managedVoiceId không được để trống.");
        }

        OperationKey key = new OperationKey(chapterId, managedVoiceId);

        // 1. Shared single-flight guard
        if (!coordinator.tryAcquire(chapterId, managedVoiceId)) {
            log.debug("Chapter narration generation for chapter [{}] and voice [{}] is already in-flight; rejecting duplicate dispatch.",
                    chapterId, managedVoiceId);
            AdminNarrationOperationState currentState = operationStates.get(key);
            if (currentState == null || currentState.status() != AdminNarrationOperationStatus.RUNNING) {
                currentState = AdminNarrationOperationState.running(chapterId, managedVoiceId, Instant.now());
            }
            return AdminNarrationDispatchResult.alreadyRunning(currentState);
        }

        // 2. Mark RUNNING state in Admin state tracker
        Instant startedAt = Instant.now();
        AdminNarrationOperationState runningState = AdminNarrationOperationState.running(chapterId, managedVoiceId, startedAt);
        operationStates.put(key, runningState);

        // 3. Submit to shared bounded background executor
        try {
            taskExecutor.execute(() -> {
                try {
                    AdminNarrationOperationState finalState = worker.runGeneration(chapterId, managedVoiceId, startedAt);
                    operationStates.put(key, finalState);
                } finally {
                    coordinator.release(chapterId, managedVoiceId);
                }
            });
            return AdminNarrationDispatchResult.started(runningState);
        } catch (RejectedExecutionException ex) {
            log.warn("Chapter narration generation rejected by executor for chapter [{}] and voice [{}]: {}",
                    chapterId, managedVoiceId, ex.getMessage());
            coordinator.release(chapterId, managedVoiceId);
            AdminNarrationOperationState rejectedState = AdminNarrationOperationState.failed(
                    chapterId, managedVoiceId, startedAt, Instant.now(), "Hệ thống đang bận. Hàng đợi tác vụ đã đầy."
            );
            operationStates.put(key, rejectedState);
            return AdminNarrationDispatchResult.rejected(rejectedState);
        } catch (RuntimeException ex) {
            log.warn("Unexpected dispatch failure for chapter [{}] and voice [{}]: {}",
                    chapterId, managedVoiceId, ex.getMessage(), ex);
            coordinator.release(chapterId, managedVoiceId);
            AdminNarrationOperationState failedState = AdminNarrationOperationState.failed(
                    chapterId, managedVoiceId, startedAt, Instant.now(), AdminNarrationGenerationWorker.SAFE_FAILURE_MESSAGE
            );
            operationStates.put(key, failedState);
            return AdminNarrationDispatchResult.failed(failedState, AdminNarrationGenerationWorker.SAFE_FAILURE_MESSAGE);
        }
    }

    /**
     * Retrieves the latest in-memory operation state for the given chapter and managed voice.
     *
     * @param chapterId      identity of the chapter
     * @param managedVoiceId identity of the managed voice
     * @return current operation state, or {@link AdminNarrationOperationStatus#IDLE} if none recorded
     */
    public AdminNarrationOperationState getOperationState(UUID chapterId, UUID managedVoiceId) {
        if (chapterId == null || managedVoiceId == null) {
            return AdminNarrationOperationState.idle(
                    chapterId != null ? chapterId : new UUID(0L, 0L),
                    managedVoiceId != null ? managedVoiceId : new UUID(0L, 0L)
            );
        }
        OperationKey key = new OperationKey(chapterId, managedVoiceId);
        if (coordinator.isInFlight(chapterId, managedVoiceId)) {
            AdminNarrationOperationState recorded = operationStates.get(key);
            if (recorded != null && recorded.status() == AdminNarrationOperationStatus.RUNNING) {
                return recorded;
            }
            return AdminNarrationOperationState.running(chapterId, managedVoiceId, Instant.now());
        }
        return operationStates.getOrDefault(key, AdminNarrationOperationState.idle(chapterId, managedVoiceId));
    }

    /**
     * Checks if a generation task is actively in-flight for the given chapter and voice.
     */
    public boolean isRunning(UUID chapterId, UUID managedVoiceId) {
        if (chapterId == null || managedVoiceId == null) {
            return false;
        }
        return coordinator.isInFlight(chapterId, managedVoiceId);
    }

    /**
     * Resets in-memory state for testing.
     */
    void clearForTesting() {
        coordinator.clearForTesting();
        operationStates.clear();
    }
}
