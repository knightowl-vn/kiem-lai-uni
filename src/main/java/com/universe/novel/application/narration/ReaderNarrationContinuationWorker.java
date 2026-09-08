package com.universe.novel.application.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Background worker that executes reader narration continuation tasks asynchronously (MS-04.9H.7C2C3B).
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Builds a fresh prioritized continuation plan via {@link BuildReaderNarrationContinuationPlanUseCase} (C3A).</li>
 *     <li>If {@code refreshRequestedSegment == true}, triggers background regeneration of the requested segment via {@link RegenerateChapterNarrationAudioUseCase} once.</li>
 *     <li>If {@code plan.hasWork() == true}, executes the remaining prioritized work items via {@link ExecuteReaderNarrationContinuationUseCase} (C2).</li>
 * </ol>
 * <p>
 * <strong>Failure & Delivery Semantics:</strong>
 * Background continuation is best-effort and non-durable. Exceptions are logged and isolated; they never crash the background thread
 * or affect caller responses.
 */
@Component
public class ReaderNarrationContinuationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReaderNarrationContinuationWorker.class);

    private final BuildReaderNarrationContinuationPlanUseCase buildPlanUseCase;
    private final RegenerateChapterNarrationAudioUseCase regenerateUseCase;
    private final ExecuteReaderNarrationContinuationUseCase executeContinuationUseCase;

    public ReaderNarrationContinuationWorker(
            BuildReaderNarrationContinuationPlanUseCase buildPlanUseCase,
            RegenerateChapterNarrationAudioUseCase regenerateUseCase,
            ExecuteReaderNarrationContinuationUseCase executeContinuationUseCase
    ) {
        this.buildPlanUseCase = Objects.requireNonNull(buildPlanUseCase, "buildPlanUseCase must not be null");
        this.regenerateUseCase = Objects.requireNonNull(regenerateUseCase, "regenerateUseCase must not be null");
        this.executeContinuationUseCase = Objects.requireNonNull(executeContinuationUseCase, "executeContinuationUseCase must not be null");
    }

    /**
     * Executes the background continuation workflow for the given command.
     *
     * @param command input background continuation command
     */
    public void runContinuation(ReaderNarrationContinuationCommand command) {
        if (command == null) {
            log.warn("Reader narration continuation worker received null command. Aborting.");
            return;
        }

        try {
            // 1. Build fresh plan from current persisted state (C3A)
            ReaderNarrationContinuationPlan plan;
            try {
                plan = buildPlanUseCase.execute(
                        command.chapterId(),
                        command.requestedSegmentId(),
                        command.managedVoiceId()
                );
            } catch (RuntimeException ex) {
                log.warn("Failed to build continuation plan for chapter [{}] and voice [{}]: {}",
                        command.chapterId(), command.managedVoiceId(), ex.getMessage(), ex);
                return;
            }

            // 2. If requested segment needs refresh, trigger background regeneration once
            if (command.refreshRequestedSegment()) {
                try {
                    regenerateUseCase.execute(command.requestedSegmentId(), command.managedVoiceId());
                } catch (RuntimeException ex) {
                    log.warn("Background refresh failed for requested segment [{}] and voice [{}]: {}",
                            command.requestedSegmentId(), command.managedVoiceId(), ex.getMessage(), ex);
                }
            }

            // 3. Execute remaining plan items through C2 orchestrator if work items exist
            if (plan.hasWork()) {
                try {
                    executeContinuationUseCase.execute(plan);
                } catch (RuntimeException ex) {
                    log.warn("Failed executing continuation plan for chapter [{}] and voice [{}]: {}",
                            command.chapterId(), command.managedVoiceId(), ex.getMessage(), ex);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Unexpected top-level error during reader continuation execution: {}", ex.getMessage(), ex);
        }
    }
}
