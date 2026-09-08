package com.universe.novel.application.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Dedicated dispatcher for non-blocking asynchronous reader narration continuation (MS-04.9H.7C2C3B).
 * <p>
 * <strong>Coalescing & Single-Flight:</strong>
 * Uses an in-memory concurrent set keyed by {@code (chapterId, managedVoiceId)}. If a continuation task for the same
 * chapter and voice is already queued or actively running, subsequent duplicate requests return {@link ReaderNarrationContinuationDispatchStatus#ALREADY_IN_FLIGHT}.
 * <p>
 * <strong>Rejection Safety:</strong>
 * If the bounded queue of the task executor is saturated, submission is rejected and the in-flight key is released immediately.
 * The task is <em>never</em> executed synchronously on the caller thread.
 */
@Component
public class ReaderNarrationContinuationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReaderNarrationContinuationDispatcher.class);

    private record InFlightKey(UUID chapterId, UUID managedVoiceId) {
        private InFlightKey {
            Objects.requireNonNull(chapterId, "chapterId must not be null");
            Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        }
    }

    private final TaskExecutor taskExecutor;
    private final ReaderNarrationContinuationWorker worker;
    private final ConcurrentMap<InFlightKey, Boolean> inFlightKeys = new ConcurrentHashMap<>();

    public ReaderNarrationContinuationDispatcher(
            @Qualifier("readerNarrationContinuationTaskExecutor") TaskExecutor taskExecutor,
            ReaderNarrationContinuationWorker worker
    ) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
    }

    /**
     * Submits a background continuation command for asynchronous execution.
     *
     * @param command the continuation command (non-null)
     * @return dispatch status
     */
    public ReaderNarrationContinuationDispatchStatus dispatch(ReaderNarrationContinuationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        InFlightKey key = new InFlightKey(command.chapterId(), command.managedVoiceId());

        // 1. Single-flight check: coalesce duplicate in-flight continuation for the same chapter + voice
        if (inFlightKeys.putIfAbsent(key, Boolean.TRUE) != null) {
            log.debug("Continuation task for chapter [{}] and voice [{}] is already in-flight; skipping duplicate submission.",
                    command.chapterId(), command.managedVoiceId());
            return ReaderNarrationContinuationDispatchStatus.ALREADY_IN_FLIGHT;
        }

        // 2. Submit to bounded executor
        try {
            taskExecutor.execute(() -> {
                try {
                    worker.runContinuation(command);
                } finally {
                    inFlightKeys.remove(key);
                }
            });
            return ReaderNarrationContinuationDispatchStatus.SCHEDULED;
        } catch (RejectedExecutionException ex) {
            log.warn("Continuation task rejected by executor for chapter [{}] and voice [{}]: {}",
                    command.chapterId(), command.managedVoiceId(), ex.getMessage());
            inFlightKeys.remove(key);
            return ReaderNarrationContinuationDispatchStatus.REJECTED;
        } catch (RuntimeException ex) {
            log.warn("Unexpected exception during continuation dispatch for chapter [{}] and voice [{}]: {}",
                    command.chapterId(), command.managedVoiceId(), ex.getMessage(), ex);
            inFlightKeys.remove(key);
            return ReaderNarrationContinuationDispatchStatus.REJECTED;
        }
    }
}
