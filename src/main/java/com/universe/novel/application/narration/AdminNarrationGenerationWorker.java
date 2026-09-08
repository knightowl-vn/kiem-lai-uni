package com.universe.novel.application.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Background worker executing chapter-level narration generation via {@link GenerateChapterNarrationUseCase} (MS-04.9H.7D4A).
 * <p>
 * <strong>Execution & Boundary Invariants:</strong>
 * <ul>
 *     <li>Delegates directly to {@link GenerateChapterNarrationUseCase} as the sole generation planning & execution authority.</li>
 *     <li>Non-transactional: does not open an outer database transaction around long-running background execution.</li>
 *     <li>No direct dependencies on TTS engines, Media, or binary storage primitives.</li>
 *     <li>Catches and isolates {@link RuntimeException}, mapping outcomes to safe {@link AdminNarrationOperationState}
 *         without exposing raw exception messages, provider IDs, or internal storage details.</li>
 * </ul>
 */
@Component
public class AdminNarrationGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(AdminNarrationGenerationWorker.class);

    public static final String SAFE_SUCCESS_MESSAGE = "Tạo giọng đọc cho chương hoàn tất thành công.";
    public static final String SAFE_PARTIAL_MESSAGE = "Tạo giọng đọc hoàn tất với một số đoạn bị lỗi.";
    public static final String SAFE_FAILURE_MESSAGE = "Tạo giọng đọc cho chương thất bại.";

    private final GenerateChapterNarrationUseCase generateChapterNarrationUseCase;

    public AdminNarrationGenerationWorker(GenerateChapterNarrationUseCase generateChapterNarrationUseCase) {
        this.generateChapterNarrationUseCase = Objects.requireNonNull(
                generateChapterNarrationUseCase, "generateChapterNarrationUseCase must not be null"
        );
    }

    /**
     * Executes chapter narration generation and resolves the final operation state.
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
            Instant completedAt = Instant.now();

            if (result.isCompleteSuccess()) {
                String msg = String.format("Hoàn tất tạo giọng đọc cho %d đoạn (đã có: %d, tạo mới: %d, cập nhật: %d).",
                        result.totalSegments(), result.skippedReadyCount(), result.generatedCount(), result.regeneratedCount());
                log.info("Chapter narration generation succeeded for chapter [{}] and voice [{}]: total={}, completed={}, skipped={}",
                        chapterId, managedVoiceId, result.totalSegments(), result.completedWorkCount(), result.skippedReadyCount());
                return AdminNarrationOperationState.succeeded(chapterId, managedVoiceId, startedAt, completedAt, msg);
            } else if (result.completedWorkCount() > 0 || result.skippedReadyCount() > 0) {
                String msg = String.format("Hoàn tất tạo giọng đọc với %d/%d đoạn thành công, %d đoạn lỗi.",
                        (result.completedWorkCount() + result.skippedReadyCount()), result.totalSegments(), result.remainingWorkCount());
                log.warn("Chapter narration generation completed partially for chapter [{}] and voice [{}]: total={}, succeeded={}, failed={}",
                        chapterId, managedVoiceId, result.totalSegments(), (result.completedWorkCount() + result.skippedReadyCount()), result.remainingWorkCount());
                return AdminNarrationOperationState.partial(chapterId, managedVoiceId, startedAt, completedAt, msg);
            } else {
                log.warn("Chapter narration generation failed for all planned segments in chapter [{}] and voice [{}]", chapterId, managedVoiceId);
                return AdminNarrationOperationState.failed(chapterId, managedVoiceId, startedAt, completedAt, SAFE_FAILURE_MESSAGE);
            }
        } catch (RuntimeException ex) {
            log.warn("Top-level exception during background chapter narration generation for chapter [{}] and voice [{}]: {}",
                    chapterId, managedVoiceId, ex.getMessage(), ex);
            return AdminNarrationOperationState.failed(chapterId, managedVoiceId, startedAt, Instant.now(), SAFE_FAILURE_MESSAGE);
        }
    }
}
