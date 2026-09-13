package com.universe.novel.application.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous single-flight dispatcher for Reader-initiated chapter narration preparation (MS-04.9H.9, H.9I5B).
 * <p>
 * <strong>Single-Flight Concurrency Control:</strong>
 * Maintains an in-memory concurrent set keyed by {@code (chapterId, managedVoiceId)}. If preparation is already
 * actively running or queued for the same key, subsequent requests return {@link ReaderChapterNarrationPreparationDispatchStatus#ALREADY_IN_FLIGHT}.
 * <p>
 * <strong>Optimization vs Correctness:</strong>
 * This in-memory map is an optimization only, not the authoritative correctness boundary. Downstream optimistic
 * locking and generation/finalization checks ensure safety across JVM instances or concurrent Admin operations.
 * <p>
 * <strong>Rejection & Thread Safety:</strong>
 * Reuses the bounded {@code readerChapterNarrationPreparationTaskExecutor}. Rejections immediately release the in-flight key.
 * Work is NEVER executed on the caller thread.
 */
@Component
public class ReaderChapterNarrationPreparationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReaderChapterNarrationPreparationDispatcher.class);

    private record OperationKey(UUID chapterId, UUID managedVoiceId) {
        private OperationKey {
            Objects.requireNonNull(chapterId, "chapterId must not be null");
            Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        }
    }

    private final TaskExecutor taskExecutor;
    private final ReaderChapterNarrationPreparationWorker worker;
    private final ConcurrentMap<OperationKey, Boolean> inFlightKeys = new ConcurrentHashMap<>();

    public ReaderChapterNarrationPreparationDispatcher(
            @Qualifier("readerChapterNarrationPreparationTaskExecutor") TaskExecutor taskExecutor,
            ReaderChapterNarrationPreparationWorker worker
    ) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
    }

    /**
     * Submits a chapter narration preparation command for asynchronous background execution.
     *
     * @param command input preparation command
     * @return dispatch status
     */
    public ReaderChapterNarrationPreparationDispatchStatus dispatch(ReaderChapterNarrationPreparationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        OperationKey key = new OperationKey(command.chapterId(), command.managedVoiceId());

        // 1. Single-flight check
        if (inFlightKeys.putIfAbsent(key, Boolean.TRUE) != null) {
            log.debug("Chapter narration preparation for chapter [{}] and voice [{}] is already in-flight; coalescing request.",
                    command.chapterId(), command.managedVoiceId());
            return ReaderChapterNarrationPreparationDispatchStatus.ALREADY_IN_FLIGHT;
        }

        // 2. Submit to bounded executor
        try {
            taskExecutor.execute(() -> {
                try {
                    worker.runPreparation(command);
                } finally {
                    inFlightKeys.remove(key);
                }
            });
            return ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED;
        } catch (RejectedExecutionException ex) {
            log.warn("Chapter narration preparation rejected by executor for chapter [{}] and voice [{}]: {}",
                    command.chapterId(), command.managedVoiceId(), ex.getMessage());
            inFlightKeys.remove(key);
            return ReaderChapterNarrationPreparationDispatchStatus.REJECTED;
        } catch (RuntimeException ex) {
            log.warn("Unexpected dispatch failure for chapter [{}] and voice [{}]: {}",
                    command.chapterId(), command.managedVoiceId(), ex.getMessage(), ex);
            inFlightKeys.remove(key);
            return ReaderChapterNarrationPreparationDispatchStatus.REJECTED;
        }
    }
}
