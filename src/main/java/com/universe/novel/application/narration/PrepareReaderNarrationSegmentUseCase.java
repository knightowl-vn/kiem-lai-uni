package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ManagedVoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reader on-demand preparation orchestrator for a single CURRENT narration segment requested for immediate playback (MS-04.9H.7C2B).
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Validates arguments and preflight requirements:
 *         <ul>
 *             <li>Chapter exists and is {@link ChapterStatus#PUBLISHED}.</li>
 *             <li>Managed voice exists and is ACTIVE.</li>
 *             <li>Segment exists, belongs to the chapter, and is in {@code CURRENT} status.</li>
 *         </ul>
 *     </li>
 *     <li>Loads initial audio assignment and failure record, and resolves initial health status.</li>
 *     <li>Plans reader preparation decision via {@link ReaderNarrationPreparationDecisionPlanner}:
 *         <ul>
 *             <li>{@link ReaderNarrationPreparationAction#PLAY_NOW} (READY) &rarr; returns cached audio immediately without generation.</li>
 *             <li>{@link ReaderNarrationPreparationAction#PLAY_NOW_AND_REFRESH} (OUTDATED) &rarr; returns cached audio immediately without synchronous regeneration; marks refresh recommended.</li>
 *             <li>{@link ReaderNarrationPreparationAction#PREPARE} (MISSING) or {@link ReaderNarrationPreparationAction#RETRY_PREPARE} (FAILED) &rarr; invokes {@link GenerateChapterNarrationAudioUseCase}.</li>
 *         </ul>
 *     </li>
 *     <li>Performs authoritative post-generation re-read across voice, segment, audio, and failure state to determine final playback outcome.</li>
 * </ol>
 * <p>
 * <strong>Transaction Boundary:</strong>
 * This orchestrator does NOT declare {@code @Transactional}. External TTS and Media operations are managed
 * exclusively within the single-segment generation primitive.
 */
@Service
public class PrepareReaderNarrationSegmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(PrepareReaderNarrationSegmentUseCase.class);

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    private final ReaderNarrationPreparationDecisionPlanner decisionPlanner;
    private final GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase;

    public PrepareReaderNarrationSegmentUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort,
            ReaderNarrationPreparationDecisionPlanner decisionPlanner,
            GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(chapterRepositoryPort, "chapterRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
        this.decisionPlanner = Objects.requireNonNull(decisionPlanner, "decisionPlanner must not be null");
        this.generateChapterNarrationAudioUseCase = Objects.requireNonNull(generateChapterNarrationAudioUseCase, "generateChapterNarrationAudioUseCase must not be null");
    }

    /**
     * Executes reader on-demand segment preparation for the given command.
     *
     * @param command input command containing chapterId, segmentId, and managedVoiceId
     * @return preparation result
     */
    public PrepareReaderNarrationSegmentResult execute(PrepareReaderNarrationSegmentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.chapterId(), command.segmentId(), command.managedVoiceId());
    }

    /**
     * Executes reader on-demand segment preparation for the given chapter, segment, and managed voice IDs.
     *
     * @param chapterId      the chapter identity
     * @param segmentId      the segment identity
     * @param managedVoiceId the managed voice identity
     * @return preparation result
     */
    public PrepareReaderNarrationSegmentResult execute(UUID chapterId, UUID segmentId, UUID managedVoiceId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        // 1. Preflight: Load Chapter and require PUBLISHED status
        Chapter chapter = chapterRepositoryPort.findById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));
        if (chapter.getStatus() != ChapterStatus.PUBLISHED) {
            throw new IllegalStateException(
                    "Chỉ có thể chuẩn bị narration cho chapter ở trạng thái PUBLISHED. Trạng thái hiện tại: " + chapter.getStatus()
            );
        }

        // 2. Preflight: Load ManagedVoice and require ACTIVE status
        ManagedVoice voice = managedVoiceRepositoryPort.findById(managedVoiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(managedVoiceId));
        if (!voice.isActive()) {
            throw new ManagedVoiceInvalidStateException(
                    "Managed voice is not in ACTIVE status: " + voice.getStatus()
            );
        }

        // 3. Preflight: Load ChapterNarrationSegment and require CURRENT status belonging to the specified chapter
        ChapterNarrationSegment segment = segmentRepositoryPort.findById(segmentId)
                .orElseThrow(() -> new ChapterNarrationSegmentNotFoundException(segmentId));
        if (!segment.getChapterId().equals(chapterId)) {
            throw new IllegalArgumentException(
                    "Segment [" + segmentId + "] does not belong to chapter [" + chapterId + "]"
            );
        }
        if (!segment.isCurrent()) {
            throw new ChapterNarrationSegmentInvalidStateException(
                    "Chapter narration segment is not in CURRENT status: " + segment.getStatus()
            );
        }

        int segmentIndex = segment.getSegmentIndex();
        long initialVoiceRevision = voice.getSynthesisRevision();

        // 4. Load initial audio assignment and failure record
        Optional<ChapterNarrationAudio> initialAudioOpt =
                audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);
        Optional<ChapterNarrationAudioFailure> initialFailureOpt =
                failureRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);

        // 5. Derive initial health and plan reader decision
        ChapterNarrationAudioHealthResolution initialResolution =
                ChapterNarrationAudioHealthResolver.resolve(
                        initialAudioOpt.orElse(null),
                        initialFailureOpt.orElse(null),
                        initialVoiceRevision
                );
        ChapterNarrationAudioHealthStatus initialHealth = initialResolution.status();
        ReaderNarrationPreparationDecision decision = decisionPlanner.plan(segmentId, segmentIndex, initialHealth);
        ReaderNarrationPreparationAction action = decision.action();

        // 6. Handle READY (PLAY_NOW) -> return cached audio immediately
        if (action == ReaderNarrationPreparationAction.PLAY_NOW) {
            UUID mediaAssetId = initialAudioOpt
                    .map(ChapterNarrationAudio::getMediaAssetId)
                    .orElse(null);
            return new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    segmentIndex,
                    managedVoiceId,
                    initialHealth,
                    action,
                    ChapterNarrationAudioHealthStatus.READY,
                    PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED,
                    mediaAssetId,
                    false,
                    false
            );
        }

        // 7. Handle OUTDATED (PLAY_NOW_AND_REFRESH) -> return cached audio immediately, recommend refresh
        if (action == ReaderNarrationPreparationAction.PLAY_NOW_AND_REFRESH) {
            UUID mediaAssetId = initialAudioOpt
                    .map(ChapterNarrationAudio::getMediaAssetId)
                    .orElse(null);
            return new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    segmentIndex,
                    managedVoiceId,
                    initialHealth,
                    action,
                    ChapterNarrationAudioHealthStatus.OUTDATED,
                    PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED,
                    mediaAssetId,
                    false,
                    true
            );
        }

        // 8. Handle PREPARE (MISSING) or RETRY_PREPARE (FAILED)
        try {
            generateChapterNarrationAudioUseCase.execute(segmentId, managedVoiceId);
        } catch (RuntimeException ex) {
            log.warn("Generation attempt threw exception for segment [{}] and voice [{}]: {}",
                    segmentId, managedVoiceId, ex.getMessage(), ex);
        }

        // 9. Authoritative Post-Generation Re-Read
        return resolvePostGenerationResult(
                chapterId,
                segmentId,
                segmentIndex,
                managedVoiceId,
                initialHealth,
                action
        );
    }

    private PrepareReaderNarrationSegmentResult resolvePostGenerationResult(
            UUID chapterId,
            UUID segmentId,
            int fallbackSegmentIndex,
            UUID managedVoiceId,
            ChapterNarrationAudioHealthStatus initialHealth,
            ReaderNarrationPreparationAction initialAction
    ) {
        // 1. Re-read Chapter and require PUBLISHED status
        Optional<Chapter> freshChapterOpt = chapterRepositoryPort.findById(chapterId);
        if (freshChapterOpt.isEmpty() || freshChapterOpt.get().getStatus() != ChapterStatus.PUBLISHED) {
            return new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    fallbackSegmentIndex,
                    managedVoiceId,
                    initialHealth,
                    initialAction,
                    null,
                    PrepareReaderNarrationSegmentOutcome.UNAVAILABLE,
                    null,
                    true,
                    false
            );
        }

        // 2. Re-read ManagedVoice
        Optional<ManagedVoice> freshVoiceOpt = managedVoiceRepositoryPort.findById(managedVoiceId);
        if (freshVoiceOpt.isEmpty() || !freshVoiceOpt.get().isActive()) {
            return new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    fallbackSegmentIndex,
                    managedVoiceId,
                    initialHealth,
                    initialAction,
                    null,
                    PrepareReaderNarrationSegmentOutcome.UNAVAILABLE,
                    null,
                    true,
                    false
            );
        }
        long freshVoiceRevision = freshVoiceOpt.get().getSynthesisRevision();

        // Re-read ChapterNarrationSegment
        Optional<ChapterNarrationSegment> freshSegmentOpt = segmentRepositoryPort.findById(segmentId);
        if (freshSegmentOpt.isEmpty()
                || !freshSegmentOpt.get().getChapterId().equals(chapterId)
                || !freshSegmentOpt.get().isCurrent()) {
            return new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    fallbackSegmentIndex,
                    managedVoiceId,
                    initialHealth,
                    initialAction,
                    null,
                    PrepareReaderNarrationSegmentOutcome.UNAVAILABLE,
                    null,
                    true,
                    false
            );
        }
        int freshSegmentIndex = freshSegmentOpt.get().getSegmentIndex();

        // Re-read ChapterNarrationAudio & Failure records
        Optional<ChapterNarrationAudio> freshAudioOpt =
                audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);
        Optional<ChapterNarrationAudioFailure> freshFailureOpt =
                failureRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);

        ChapterNarrationAudioHealthResolution freshResolution =
                ChapterNarrationAudioHealthResolver.resolve(
                        freshAudioOpt.orElse(null),
                        freshFailureOpt.orElse(null),
                        freshVoiceRevision
                );
        ChapterNarrationAudioHealthStatus finalHealth = freshResolution.status();

        return switch (finalHealth) {
            case READY -> new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    freshSegmentIndex,
                    managedVoiceId,
                    initialHealth,
                    initialAction,
                    finalHealth,
                    PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE,
                    freshAudioOpt.map(ChapterNarrationAudio::getMediaAssetId).orElse(null),
                    true,
                    false
            );
            case OUTDATED -> new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    freshSegmentIndex,
                    managedVoiceId,
                    initialHealth,
                    initialAction,
                    finalHealth,
                    PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE,
                    freshAudioOpt.map(ChapterNarrationAudio::getMediaAssetId).orElse(null),
                    true,
                    true
            );
            case FAILED -> new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    freshSegmentIndex,
                    managedVoiceId,
                    initialHealth,
                    initialAction,
                    finalHealth,
                    PrepareReaderNarrationSegmentOutcome.RETRY_REQUIRED,
                    null,
                    true,
                    false
            );
            case MISSING -> new PrepareReaderNarrationSegmentResult(
                    chapterId,
                    segmentId,
                    freshSegmentIndex,
                    managedVoiceId,
                    initialHealth,
                    initialAction,
                    finalHealth,
                    PrepareReaderNarrationSegmentOutcome.PREPARATION_FAILED,
                    null,
                    true,
                    false
            );
        };
    }
}
