package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ManagedVoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrator that executes an already-planned reader narration continuation plan (MS-04.9H.7C2C2).
 * <p>
 * <strong>Execution Invariants:</strong>
 * <ol>
 *     <li><strong>Preflight:</strong> Validates chapter published status, voice active status, manifest existence/token, and requested segment state.</li>
 *     <li><strong>Per-Item Guard:</strong> Re-reads manifest, chapter, voice, and planned segment before and after each primitive call.</li>
 *     <li><strong>Fresh Health Authority:</strong> Always re-evaluates fresh audio health prior to dispatch. Ready items are skipped; missing/failed trigger generation; outdated trigger regeneration.</li>
 *     <li><strong>Failure Isolation:</strong> Catches {@link RuntimeException} during primitive execution and falls back to authoritative post-execution state resolution.</li>
 *     <li><strong>Strict Plan Order:</strong> Executes work items in the exact prioritized order defined by {@link ReaderNarrationContinuationPlan#workItems()}.</li>
 * </ol>
 * <p>
 * <strong>Transaction Boundary:</strong>
 * This orchestrator is strictly non-transactional (no {@code @Transactional}). Transactional boundaries and external I/O
 * are encapsulated entirely within the single-segment generation and regeneration primitives.
 */
@Service
public class ExecuteReaderNarrationContinuationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteReaderNarrationContinuationUseCase.class);

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationManifestRepositoryPort manifestRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    private final GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase;
    private final RegenerateChapterNarrationAudioUseCase regenerateChapterNarrationAudioUseCase;

    public ExecuteReaderNarrationContinuationUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationManifestRepositoryPort manifestRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort,
            GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase,
            RegenerateChapterNarrationAudioUseCase regenerateChapterNarrationAudioUseCase
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(chapterRepositoryPort, "chapterRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.manifestRepositoryPort = Objects.requireNonNull(manifestRepositoryPort, "manifestRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
        this.generateChapterNarrationAudioUseCase = Objects.requireNonNull(generateChapterNarrationAudioUseCase, "generateChapterNarrationAudioUseCase must not be null");
        this.regenerateChapterNarrationAudioUseCase = Objects.requireNonNull(regenerateChapterNarrationAudioUseCase, "regenerateChapterNarrationAudioUseCase must not be null");
    }

    /**
     * Executes the continuation plan.
     *
     * @param plan the pre-computed reader narration continuation plan (non-null)
     * @return immutable execution result
     */
    public ExecuteReaderNarrationContinuationResult execute(ReaderNarrationContinuationPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }

        UUID chapterId = plan.chapterId();
        UUID managedVoiceId = plan.managedVoiceId();
        UUID requestedSegmentId = plan.requestedSegmentId();

        // 1. Preflight: Load Chapter and require PUBLISHED
        Chapter chapter = chapterRepositoryPort.findById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));
        if (chapter.getStatus() != ChapterStatus.PUBLISHED) {
            throw new IllegalStateException(
                    "Chỉ có thể thực hiện narration continuation cho chapter ở trạng thái PUBLISHED. Trạng thái hiện tại: " + chapter.getStatus()
            );
        }

        // 2. Preflight: Load ManagedVoice and require ACTIVE
        ManagedVoice voice = managedVoiceRepositoryPort.findById(managedVoiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(managedVoiceId));
        if (!voice.isActive()) {
            throw new ManagedVoiceInvalidStateException(
                    "Managed voice is not in ACTIVE status: " + voice.getStatus()
            );
        }

        // 3. Preflight: Load ChapterNarrationManifest and capture token
        ChapterNarrationManifest initialManifest = manifestRepositoryPort.findByChapterId(chapterId)
                .orElseThrow(() -> new IllegalStateException(
                        "Chapter narration manifest not found for chapter: " + chapterId
                ));
        long initialSourceContentVersion = initialManifest.getSourceContentVersion();
        String initialManifestHash = initialManifest.getManifestHash();

        // 4. Preflight: Validate requested segment
        ChapterNarrationSegment requestedSegment = segmentRepositoryPort.findById(requestedSegmentId)
                .orElseThrow(() -> new ChapterNarrationSegmentNotFoundException(requestedSegmentId));
        if (!requestedSegment.getChapterId().equals(chapterId)) {
            throw new IllegalArgumentException(
                    "Requested segment [" + requestedSegmentId + "] does not belong to chapter [" + chapterId + "]"
            );
        }
        if (!requestedSegment.isCurrent()) {
            throw new ChapterNarrationSegmentInvalidStateException(
                    "Requested narration segment is not in CURRENT status: " + requestedSegment.getStatus()
            );
        }

        // 5. Preflight: Ensure requested segment is never present in continuation work items
        for (ReaderNarrationContinuationPlanItem item : plan.workItems()) {
            if (item.segmentId().equals(requestedSegmentId)) {
                throw new IllegalArgumentException(
                        "Malformed plan: requested segment [" + requestedSegmentId + "] must not be included in continuation work items"
                );
            }
        }

        List<ReaderNarrationContinuationPlanItem> workItems = plan.workItems();
        List<ReaderNarrationContinuationItemResult> itemResults = new ArrayList<>(workItems.size());
        ReaderNarrationContinuationExecutionStatus executionStatus = ReaderNarrationContinuationExecutionStatus.COMPLETED;

        for (ReaderNarrationContinuationPlanItem item : workItems) {
            UUID segmentId = item.segmentId();
            int segmentIndex = item.segmentIndex();
            ReaderNarrationContinuationAction plannedAction = item.action();

            // Guard 1: Manifest check before execution
            Optional<ChapterNarrationManifest> manifestOpt = manifestRepositoryPort.findByChapterId(chapterId);
            if (manifestOpt.isEmpty()
                    || manifestOpt.get().getSourceContentVersion() != initialSourceContentVersion
                    || !Objects.equals(manifestOpt.get().getManifestHash(), initialManifestHash)) {
                log.warn("Chapter narration manifest changed or missing before processing segment [{}]. Aborting continuation.", segmentId);
                executionStatus = ReaderNarrationContinuationExecutionStatus.MANIFEST_CHANGED;
                break;
            }

            // Guard 2: Chapter check before execution
            Optional<Chapter> freshChapterOpt = chapterRepositoryPort.findById(chapterId);
            if (freshChapterOpt.isEmpty() || freshChapterOpt.get().getStatus() != ChapterStatus.PUBLISHED) {
                log.warn("Chapter [{}] unpublished or missing before processing segment [{}]. Aborting continuation.", chapterId, segmentId);
                executionStatus = ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE;
                break;
            }

            // Guard 3: Voice check before execution
            Optional<ManagedVoice> freshVoiceOpt = managedVoiceRepositoryPort.findById(managedVoiceId);
            if (freshVoiceOpt.isEmpty() || !freshVoiceOpt.get().isActive()) {
                log.warn("Managed voice [{}] inactive or missing before processing segment [{}]. Aborting continuation.", managedVoiceId, segmentId);
                executionStatus = ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE;
                break;
            }
            long currentVoiceRevision = freshVoiceOpt.get().getSynthesisRevision();

            // Guard 4: Planned Segment check before execution
            Optional<ChapterNarrationSegment> freshSegmentOpt = segmentRepositoryPort.findById(segmentId);
            if (freshSegmentOpt.isEmpty()
                    || !freshSegmentOpt.get().getChapterId().equals(chapterId)
                    || !freshSegmentOpt.get().isCurrent()) {
                log.warn("Segment [{}] retired, re-associated, or missing before execution. Marking STALE_CONTEXT and aborting.", segmentId);
                itemResults.add(new ReaderNarrationContinuationItemResult(
                        segmentId,
                        segmentIndex,
                        plannedAction,
                        null,
                        null,
                        null,
                        ReaderNarrationContinuationItemOutcome.STALE_CONTEXT
                ));
                executionStatus = ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE;
                break;
            }

            // Fresh Health check before execution
            Optional<ChapterNarrationAudio> preAudioOpt = audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);
            Optional<ChapterNarrationAudioFailure> preFailureOpt = failureRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);

            ChapterNarrationAudioHealthResolution preHealthRes = ChapterNarrationAudioHealthResolver.resolve(
                    preAudioOpt.orElse(null),
                    preFailureOpt.orElse(null),
                    currentVoiceRevision
            );
            ChapterNarrationAudioHealthStatus freshHealth = preHealthRes.status();

            // Fresh Health READY -> Skip external work
            if (freshHealth == ChapterNarrationAudioHealthStatus.READY) {
                itemResults.add(new ReaderNarrationContinuationItemResult(
                        segmentId,
                        segmentIndex,
                        plannedAction,
                        freshHealth,
                        null,
                        ChapterNarrationAudioHealthStatus.READY,
                        ReaderNarrationContinuationItemOutcome.SKIPPED_ALREADY_READY
                ));
                continue;
            }

            // Determine fresh execution action
            ReaderNarrationContinuationAction executedAction;
            if (freshHealth == ChapterNarrationAudioHealthStatus.OUTDATED) {
                executedAction = ReaderNarrationContinuationAction.REGENERATE;
            } else {
                executedAction = ReaderNarrationContinuationAction.GENERATE;
            }

            // Execute primitive with failure isolation
            try {
                if (executedAction == ReaderNarrationContinuationAction.REGENERATE) {
                    regenerateChapterNarrationAudioUseCase.execute(segmentId, managedVoiceId);
                } else {
                    generateChapterNarrationAudioUseCase.execute(segmentId, managedVoiceId);
                }
            } catch (RuntimeException ex) {
                log.warn("Continuation generation attempt failed for segment [{}] and voice [{}]: {}",
                        segmentId, managedVoiceId, ex.getMessage(), ex);
            }

            // Authoritative Post-Execution Re-Read
            // 1. Post-manifest check
            Optional<ChapterNarrationManifest> postManifestOpt = manifestRepositoryPort.findByChapterId(chapterId);
            if (postManifestOpt.isEmpty()
                    || postManifestOpt.get().getSourceContentVersion() != initialSourceContentVersion
                    || !Objects.equals(postManifestOpt.get().getManifestHash(), initialManifestHash)) {
                log.warn("Manifest changed or disappeared after processing segment [{}]. Aborting continuation.", segmentId);
                itemResults.add(new ReaderNarrationContinuationItemResult(
                        segmentId,
                        segmentIndex,
                        plannedAction,
                        freshHealth,
                        executedAction,
                        null,
                        ReaderNarrationContinuationItemOutcome.STALE_CONTEXT
                ));
                executionStatus = ReaderNarrationContinuationExecutionStatus.MANIFEST_CHANGED;
                break;
            }

            // 2. Post-chapter check
            Optional<Chapter> postChapterOpt = chapterRepositoryPort.findById(chapterId);
            if (postChapterOpt.isEmpty() || postChapterOpt.get().getStatus() != ChapterStatus.PUBLISHED) {
                log.warn("Chapter [{}] became unavailable after processing segment [{}]. Aborting continuation.", chapterId, segmentId);
                itemResults.add(new ReaderNarrationContinuationItemResult(
                        segmentId,
                        segmentIndex,
                        plannedAction,
                        freshHealth,
                        executedAction,
                        null,
                        ReaderNarrationContinuationItemOutcome.STALE_CONTEXT
                ));
                executionStatus = ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE;
                break;
            }

            // 3. Post-voice check
            Optional<ManagedVoice> postVoiceOpt = managedVoiceRepositoryPort.findById(managedVoiceId);
            if (postVoiceOpt.isEmpty() || !postVoiceOpt.get().isActive()) {
                log.warn("Managed voice [{}] became inactive after processing segment [{}]. Aborting continuation.", managedVoiceId, segmentId);
                itemResults.add(new ReaderNarrationContinuationItemResult(
                        segmentId,
                        segmentIndex,
                        plannedAction,
                        freshHealth,
                        executedAction,
                        null,
                        ReaderNarrationContinuationItemOutcome.STALE_CONTEXT
                ));
                executionStatus = ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE;
                break;
            }
            long postVoiceRevision = postVoiceOpt.get().getSynthesisRevision();

            // 4. Post-segment check
            Optional<ChapterNarrationSegment> postSegmentOpt = segmentRepositoryPort.findById(segmentId);
            if (postSegmentOpt.isEmpty()
                    || !postSegmentOpt.get().getChapterId().equals(chapterId)
                    || !postSegmentOpt.get().isCurrent()) {
                log.warn("Segment [{}] retired, re-associated, or missing after execution. Marking STALE_CONTEXT and aborting.", segmentId);
                itemResults.add(new ReaderNarrationContinuationItemResult(
                        segmentId,
                        segmentIndex,
                        plannedAction,
                        freshHealth,
                        executedAction,
                        null,
                        ReaderNarrationContinuationItemOutcome.STALE_CONTEXT
                ));
                executionStatus = ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE;
                break;
            }

            // 5. Post-audio & failure check
            Optional<ChapterNarrationAudio> postAudioOpt = audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);
            Optional<ChapterNarrationAudioFailure> postFailureOpt = failureRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);

            ChapterNarrationAudioHealthResolution postHealthRes = ChapterNarrationAudioHealthResolver.resolve(
                    postAudioOpt.orElse(null),
                    postFailureOpt.orElse(null),
                    postVoiceRevision
            );
            ChapterNarrationAudioHealthStatus finalHealth = postHealthRes.status();

            ReaderNarrationContinuationItemOutcome itemOutcome = switch (finalHealth) {
                case READY -> ReaderNarrationContinuationItemOutcome.COMPLETED_READY;
                case OUTDATED -> ReaderNarrationContinuationItemOutcome.COMPLETED_OUTDATED;
                case FAILED -> ReaderNarrationContinuationItemOutcome.RETRY_REQUIRED;
                case MISSING -> ReaderNarrationContinuationItemOutcome.FAILED;
            };

            itemResults.add(new ReaderNarrationContinuationItemResult(
                    segmentId,
                    segmentIndex,
                    plannedAction,
                    freshHealth,
                    executedAction,
                    finalHealth,
                    itemOutcome
            ));
        }

        if (executionStatus == ReaderNarrationContinuationExecutionStatus.COMPLETED) {
            boolean hasFailures = itemResults.stream().anyMatch(ReaderNarrationContinuationItemResult::isFailed);
            if (hasFailures) {
                executionStatus = ReaderNarrationContinuationExecutionStatus.PARTIAL;
            }
        }

        return new ExecuteReaderNarrationContinuationResult(
                chapterId,
                managedVoiceId,
                requestedSegmentId,
                workItems.size(),
                itemResults,
                executionStatus
        );
    }
}
