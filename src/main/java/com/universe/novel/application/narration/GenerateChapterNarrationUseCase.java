package com.universe.novel.application.narration;

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
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Chapter-level narration generation orchestrator for a PUBLISHED Chapter and an ACTIVE ManagedVoice (MS-04.9H.7C1B).
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Validates arguments and preflight conditions:
 *         <ul>
 *             <li>Chapter exists and is in {@link ChapterStatus#PUBLISHED} status.</li>
 *             <li>Managed voice exists and is active.</li>
 *         </ul>
 *     </li>
 *     <li>Loads all {@code CURRENT} segments for the chapter and sorts them by {@code segmentIndex ASC}.</li>
 *     <li>Batch-loads audio assignments and failure records (zero N+1 queries).</li>
 *     <li>Derives health status for each segment and invokes {@link ChapterNarrationGenerationPlanner} exactly once.</li>
 *     <li>Sequentially processes planned items in {@code segmentIndex ASC} order:
 *         <ul>
 *             <li>{@link ChapterNarrationGenerationAction#SKIP_READY} &rarr; records skipped outcome without calling primitives.</li>
 *             <li>{@link ChapterNarrationGenerationAction#GENERATE} &rarr; calls {@link GenerateChapterNarrationAudioUseCase}.</li>
 *             <li>{@link ChapterNarrationGenerationAction#REGENERATE} &rarr; calls {@link RegenerateChapterNarrationAudioUseCase}.</li>
 *         </ul>
 *     </li>
 *     <li>Isolates per-segment exceptions so one failure does not abort remaining segments in the chapter.</li>
 * </ol>
 * <p>
 * <strong>Transaction Boundary:</strong> This orchestrator does NOT hold an active database transaction across the chapter loop.
 * Each segment generation primitive manages its own external I/O and transactional persistence.
 */
@Service
public class GenerateChapterNarrationUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateChapterNarrationUseCase.class);

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    private final ChapterNarrationGenerationPlanner generationPlanner;
    private final GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase;
    private final RegenerateChapterNarrationAudioUseCase regenerateChapterNarrationAudioUseCase;
    private final CleanupCompletedChapterNarrationRetiredAudioUseCase cleanupCompletedChapterNarrationRetiredAudioUseCase;

    public GenerateChapterNarrationUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort,
            ChapterNarrationGenerationPlanner generationPlanner,
            GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase,
            RegenerateChapterNarrationAudioUseCase regenerateChapterNarrationAudioUseCase,
            CleanupCompletedChapterNarrationRetiredAudioUseCase cleanupCompletedChapterNarrationRetiredAudioUseCase
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(chapterRepositoryPort, "chapterRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
        this.generationPlanner = Objects.requireNonNull(generationPlanner, "generationPlanner must not be null");
        this.generateChapterNarrationAudioUseCase = Objects.requireNonNull(generateChapterNarrationAudioUseCase, "generateChapterNarrationAudioUseCase must not be null");
        this.regenerateChapterNarrationAudioUseCase = Objects.requireNonNull(regenerateChapterNarrationAudioUseCase, "regenerateChapterNarrationAudioUseCase must not be null");
        this.cleanupCompletedChapterNarrationRetiredAudioUseCase = Objects.requireNonNull(cleanupCompletedChapterNarrationRetiredAudioUseCase, "cleanupCompletedChapterNarrationRetiredAudioUseCase must not be null");
    }

    /**
     * Executes chapter-level narration generation for the given command.
     *
     * @param command input command containing chapterId and managedVoiceId
     * @return execution result record
     */
    public GenerateChapterNarrationResult execute(GenerateChapterNarrationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.chapterId(), command.managedVoiceId());
    }

    /**
     * Executes chapter-level narration generation for the given chapter ID and managed voice ID.
     *
     * @param chapterId      identity of the published chapter
     * @param managedVoiceId identity of the active managed voice
     * @return execution result record
     */
    public GenerateChapterNarrationResult execute(UUID chapterId, UUID managedVoiceId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        // 1. Preflight Chapter validation
        Chapter chapter = chapterRepositoryPort.findById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        if (chapter.getStatus() != ChapterStatus.PUBLISHED) {
            throw new IllegalStateException(
                    "Chỉ có thể tạo narration cho chapter ở trạng thái PUBLISHED. Trạng thái hiện tại: " + chapter.getStatus()
            );
        }

        // 2. Preflight ManagedVoice validation
        ManagedVoice voice = managedVoiceRepositoryPort.findById(managedVoiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(managedVoiceId));

        if (!voice.isActive()) {
            throw new ManagedVoiceInvalidStateException(
                    "Managed voice is not in ACTIVE status: " + voice.getStatus()
            );
        }

        // 3. Load CURRENT narration segments (guarantee deterministic sort by segmentIndex ASC)
        List<ChapterNarrationSegment> currentSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                chapterId,
                ChapterNarrationSegmentStatus.CURRENT
        );

        if (currentSegments.isEmpty()) {
            return new GenerateChapterNarrationResult(
                    chapterId,
                    managedVoiceId,
                    Collections.emptyList(),
                    ChapterNarrationCompletionCleanupSummary.notEligible()
            );
        }

        List<ChapterNarrationSegment> sortedSegments = currentSegments.stream()
                .sorted(Comparator.comparingInt(ChapterNarrationSegment::getSegmentIndex))
                .toList();

        // 4. Batch-load audio assignments and failure records for CURRENT segments (Zero N+1)
        List<UUID> segmentIds = sortedSegments.stream()
                .map(ChapterNarrationSegment::getId)
                .toList();

        Map<UUID, ChapterNarrationAudio> audioBySegmentId = audioRepositoryPort
                .findBySegmentIdInAndManagedVoiceId(segmentIds, managedVoiceId)
                .stream()
                .collect(Collectors.toMap(ChapterNarrationAudio::getSegmentId, a -> a, (a1, a2) -> a1));

        Map<UUID, ChapterNarrationAudioFailure> failureBySegmentId = failureRepositoryPort
                .findBySegmentIdInAndManagedVoiceId(segmentIds, managedVoiceId)
                .stream()
                .collect(Collectors.toMap(ChapterNarrationAudioFailure::getSegmentId, f -> f, (f1, f2) -> f1));

        long currentVoiceRevision = voice.getSynthesisRevision();

        // 5. Derive health snapshots and build generation plan
        List<ChapterNarrationSegmentHealthSnapshot> snapshots = new ArrayList<>(sortedSegments.size());
        for (ChapterNarrationSegment segment : sortedSegments) {
            ChapterNarrationAudio audio = audioBySegmentId.get(segment.getId());
            ChapterNarrationAudioFailure failure = failureBySegmentId.get(segment.getId());

            ChapterNarrationAudioHealthResolution resolution =
                    ChapterNarrationAudioHealthResolver.resolve(audio, failure, currentVoiceRevision);

            snapshots.add(new ChapterNarrationSegmentHealthSnapshot(
                    segment.getId(),
                    segment.getSegmentIndex(),
                    resolution.status()
            ));
        }

        ChapterNarrationGenerationPlan plan = generationPlanner.plan(chapterId, managedVoiceId, snapshots);

        // 6. Execute plan items sequentially in segmentIndex ASC order with failure isolation
        List<ChapterNarrationSegmentExecutionResult> executionResults = new ArrayList<>(plan.items().size());

        for (ChapterNarrationGenerationPlanItem item : plan.items()) {
            UUID segmentId = item.segmentId();
            int segmentIndex = item.segmentIndex();
            ChapterNarrationGenerationAction action = item.action();

            switch (action) {
                case SKIP_READY -> executionResults.add(
                        ChapterNarrationSegmentExecutionResult.skippedReady(segmentId, segmentIndex)
                );
                case GENERATE -> executionResults.add(
                        executeGenerate(segmentId, segmentIndex, managedVoiceId)
                );
                case REGENERATE -> executionResults.add(
                        executeRegenerate(segmentId, segmentIndex, managedVoiceId)
                );
            }
        }

        // 7. Coordinate completion cleanup for retired audio (MS-04.9H.7C1C2)
        ChapterNarrationCompletionCleanupSummary cleanupSummary;
        try {
            cleanupSummary = cleanupCompletedChapterNarrationRetiredAudioUseCase.execute(chapterId, managedVoiceId);
        } catch (RuntimeException ex) {
            log.warn("Unexpected failure during completion cleanup coordinator execution for chapter [{}] and voice [{}]: {}",
                    chapterId, managedVoiceId, ex.getMessage(), ex);
            cleanupSummary = ChapterNarrationCompletionCleanupSummary.coordinatorFailed();
        }

        return new GenerateChapterNarrationResult(chapterId, managedVoiceId, executionResults, cleanupSummary);
    }

    public static final String SAFE_EXECUTION_ERROR_MESSAGE = "Narration audio generation failed.";

    private ChapterNarrationSegmentExecutionResult executeGenerate(UUID segmentId, int segmentIndex, UUID managedVoiceId) {
        try {
            GenerateChapterNarrationAudioResult genResult =
                    generateChapterNarrationAudioUseCase.execute(segmentId, managedVoiceId);
            ChapterNarrationGenerationExecutionOutcome outcome = mapGenerateOutcome(genResult.outcome());
            return ChapterNarrationSegmentExecutionResult.success(
                    segmentId,
                    segmentIndex,
                    ChapterNarrationGenerationAction.GENERATE,
                    outcome
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to generate narration audio for segment [{}] and voice [{}]: {}",
                    segmentId, managedVoiceId, ex.getMessage(), ex);
            return ChapterNarrationSegmentExecutionResult.failure(
                    segmentId,
                    segmentIndex,
                    ChapterNarrationGenerationAction.GENERATE,
                    ChapterNarrationAudioFailure.sanitizeErrorType(ex),
                    SAFE_EXECUTION_ERROR_MESSAGE
            );
        }
    }

    private ChapterNarrationSegmentExecutionResult executeRegenerate(UUID segmentId, int segmentIndex, UUID managedVoiceId) {
        try {
            RegenerateChapterNarrationAudioResult regenResult =
                    regenerateChapterNarrationAudioUseCase.execute(segmentId, managedVoiceId);
            ChapterNarrationGenerationExecutionOutcome outcome = mapRegenerateOutcome(regenResult.outcome());
            return ChapterNarrationSegmentExecutionResult.success(
                    segmentId,
                    segmentIndex,
                    ChapterNarrationGenerationAction.REGENERATE,
                    outcome
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to regenerate narration audio for segment [{}] and voice [{}]: {}",
                    segmentId, managedVoiceId, ex.getMessage(), ex);
            return ChapterNarrationSegmentExecutionResult.failure(
                    segmentId,
                    segmentIndex,
                    ChapterNarrationGenerationAction.REGENERATE,
                    ChapterNarrationAudioFailure.sanitizeErrorType(ex),
                    SAFE_EXECUTION_ERROR_MESSAGE
            );
        }
    }

    private ChapterNarrationGenerationExecutionOutcome mapGenerateOutcome(NarrationAudioGenerationOutcome outcome) {
        return switch (outcome) {
            case GENERATED -> ChapterNarrationGenerationExecutionOutcome.GENERATED;
            case REUSED -> ChapterNarrationGenerationExecutionOutcome.REUSED;
            case STALE -> ChapterNarrationGenerationExecutionOutcome.RETRY_REQUIRED;
        };
    }

    private ChapterNarrationGenerationExecutionOutcome mapRegenerateOutcome(RegenerateNarrationAudioOutcome outcome) {
        return switch (outcome) {
            case REGENERATED -> ChapterNarrationGenerationExecutionOutcome.REGENERATED;
            case ALREADY_CURRENT -> ChapterNarrationGenerationExecutionOutcome.ALREADY_CURRENT;
        };
    }
}
