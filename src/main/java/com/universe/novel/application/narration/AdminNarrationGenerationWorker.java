package com.universe.novel.application.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Background worker ensuring segment readiness, then building and publishing chapter playback.
 * <p>
 * <strong>Execution & Boundary Invariants:</strong>
 * <ul>
 *     <li>Delegates directly to {@link GenerateChapterNarrationUseCase} as the sole generation planning & execution authority.</li>
 *     <li>Delegates chapter playback publication to {@link BuildChapterNarrationPlaybackUseCase} only after segment readiness succeeds.</li>
 *     <li>Non-transactional: does not open an outer database transaction around long-running background execution.</li>
 *     <li>No direct dependencies on TTS engines, Media, or binary storage primitives.</li>
 *     <li>Catches and isolates {@link RuntimeException}, mapping outcomes to safe {@link AdminNarrationOperationState}
 *         without exposing raw exception messages, provider IDs, or internal storage details.</li>
 * </ul>
 */
@Component
public class AdminNarrationGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(AdminNarrationGenerationWorker.class);

    public static final String SAFE_FAILURE_MESSAGE = "Tạo / cập nhật audio cả chương thất bại. Vui lòng thử lại.";
    public static final String SAFE_ALREADY_CURRENT_MESSAGE = "Audio cả chương đã là phiên bản hiện tại, không cần tạo lại.";

    private final GenerateChapterNarrationUseCase generateChapterNarrationUseCase;
    private final BuildChapterNarrationPlaybackUseCase buildChapterNarrationPlaybackUseCase;

    public AdminNarrationGenerationWorker(
            GenerateChapterNarrationUseCase generateChapterNarrationUseCase,
            BuildChapterNarrationPlaybackUseCase buildChapterNarrationPlaybackUseCase
    ) {
        this.generateChapterNarrationUseCase = Objects.requireNonNull(
                generateChapterNarrationUseCase, "generateChapterNarrationUseCase must not be null"
        );
        this.buildChapterNarrationPlaybackUseCase = Objects.requireNonNull(
                buildChapterNarrationPlaybackUseCase, "buildChapterNarrationPlaybackUseCase must not be null"
        );
    }

    /**
     * Ensures segment readiness and publishes chapter playback before resolving the final operation state.
     *
     * @param chapterId      identity of the published chapter
     * @param managedVoiceId identity of the active managed voice
     * @param startedAt      timestamp when execution was scheduled/started
     * @return resulting operation state snapshot
     */
    public AdminNarrationOperationState runGeneration(UUID chapterId, UUID managedVoiceId, Instant startedAt) {
        if (chapterId == null || managedVoiceId == null) {
            log.warn("Admin narration worker received null identifiers. chapterId=[{}], voiceId=[{}]", chapterId, managedVoiceId);
            return AdminNarrationOperationState.failed(
                    chapterId != null ? chapterId : new UUID(0L, 0L),
                    managedVoiceId != null ? managedVoiceId : new UUID(0L, 0L),
                    startedAt != null ? startedAt : Instant.now(),
                    Instant.now(),
                    SAFE_FAILURE_MESSAGE
            );
        }

        try {
            log.info("Starting background chapter narration generation for chapter [{}] and voice [{}]", chapterId, managedVoiceId);
            GenerateChapterNarrationResult result = generateChapterNarrationUseCase.execute(chapterId, managedVoiceId);
            if (!result.isCompleteSuccess()) {
                log.warn("Chapter narration readiness incomplete for chapter [{}] and voice [{}]: remaining={}",
                        chapterId, managedVoiceId, result.remainingWorkCount());
                return AdminNarrationOperationState.failed(chapterId, managedVoiceId, startedAt, Instant.now(), SAFE_FAILURE_MESSAGE);
            }

            BuildChapterNarrationPlaybackResult playbackResult = buildChapterNarrationPlaybackUseCase.execute(
                    new BuildChapterNarrationPlaybackCommand(chapterId, managedVoiceId));
            if (playbackResult.outcome() == BuildChapterNarrationPlaybackOutcome.ALREADY_CURRENT) {
                return AdminNarrationOperationState.succeeded(chapterId, managedVoiceId, startedAt,
                        Instant.now(), SAFE_ALREADY_CURRENT_MESSAGE);
            }

            String msg = String.format("Hoàn tất tạo / cập nhật audio cả chương với %d đoạn (đã có: %d, tạo mới: %d, cập nhật: %d).",
                    result.totalSegments(), result.skippedReadyCount(), result.generatedCount(), result.regeneratedCount());
            log.info("Chapter narration playback published for chapter [{}] and voice [{}]: total={}, completed={}, skipped={}",
                    chapterId, managedVoiceId, result.totalSegments(), result.completedWorkCount(), result.skippedReadyCount());
            return AdminNarrationOperationState.succeeded(chapterId, managedVoiceId, startedAt, Instant.now(), msg);
        } catch (RuntimeException ex) {
            log.warn("Top-level exception during background chapter narration generation for chapter [{}] and voice [{}]: {}",
                    chapterId, managedVoiceId, ex.getMessage(), ex);
            return AdminNarrationOperationState.failed(chapterId, managedVoiceId, startedAt, Instant.now(), SAFE_FAILURE_MESSAGE);
        }
    }
}
