package com.universe.novel.application.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous single-flight dispatcher for Reader-initiated chapter narration preparation (MS-04.9H.9, H.9I5B, H.10A1).
 * <p>
 * <strong>Single-Flight Concurrency Control:</strong>
 * Delegates single-flight ownership to the shared {@link ChapterNarrationExecutionCoordinator} keyed by {@code (chapterId, managedVoiceId)}.
 * If preparation or generation is already actively running or queued for the same key across Reader or Admin domains,
 * subsequent requests return {@link ReaderChapterNarrationPreparationDispatchStatus#ALREADY_IN_FLIGHT}.
 * <p>
 * <strong>Rejection & Thread Safety:</strong>
 * Reuses the shared bounded {@code chapterNarrationExecutionTaskExecutor}. Rejections immediately release the in-flight key.
 * Work is NEVER executed on the caller thread.
 */
@Component
public class ReaderChapterNarrationPreparationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReaderChapterNarrationPreparationDispatcher.class);

    private final TaskExecutor taskExecutor;
    private final ReaderChapterNarrationPreparationWorker worker;
    private final ChapterNarrationExecutionCoordinator coordinator;

    public ReaderChapterNarrationPreparationDispatcher(
            @Qualifier("chapterNarrationExecutionTaskExecutor") TaskExecutor taskExecutor,
            ReaderChapterNarrationPreparationWorker worker,
            ChapterNarrationExecutionCoordinator coordinator
    ) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
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

        UUID chapterId = command.chapterId();
        UUID managedVoiceId = command.managedVoiceId();

        // 1. Shared single-flight check
        if (!coordinator.tryAcquire(chapterId, managedVoiceId)) {
            log.debug("Chapter narration preparation for chapter [{}] and voice [{}] is already in-flight; coalescing request.",
                    chapterId, managedVoiceId);
            return ReaderChapterNarrationPreparationDispatchStatus.ALREADY_IN_FLIGHT;
        }

        // 2. Submit to shared bounded executor
        try {
            taskExecutor.execute(() -> {
                try {
                    worker.runPreparation(command);
                } finally {
                    coordinator.release(chapterId, managedVoiceId);
                }
            });
            return ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED;
        } catch (RejectedExecutionException ex) {
            log.warn("Chapter narration preparation rejected by executor for chapter [{}] and voice [{}]: {}",
                    chapterId, managedVoiceId, ex.getMessage());
            coordinator.release(chapterId, managedVoiceId);
            return ReaderChapterNarrationPreparationDispatchStatus.REJECTED;
        } catch (RuntimeException ex) {
            log.warn("Unexpected dispatch failure for chapter [{}] and voice [{}]: {}",
                    chapterId, managedVoiceId, ex.getMessage(), ex);
            coordinator.release(chapterId, managedVoiceId);
            return ReaderChapterNarrationPreparationDispatchStatus.REJECTED;
        }
    }
}
