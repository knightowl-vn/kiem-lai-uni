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
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side application service that builds a fresh {@link ReaderNarrationContinuationPlan} from persisted state (MS-04.9H.7C2C3A).
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Validates arguments and preflight requirements:
 *         <ul>
 *             <li>Chapter exists and is in {@link ChapterStatus#PUBLISHED} status.</li>
 *             <li>Managed voice exists and is ACTIVE.</li>
 *             <li>Requested segment exists, belongs to the chapter, and is in {@code CURRENT} status.</li>
 *         </ul>
 *     </li>
 *     <li>Loads all {@code CURRENT} narration segments for the chapter.</li>
 *     <li>Batch-loads audio assignments and failure records for all {@code CURRENT} segment IDs (preventing N+1 queries).</li>
 *     <li>Resolves health for each {@code CURRENT} segment using the current voice synthesis revision.</li>
 *     <li>Creates snapshots and delegates priority bucketing and ordering to {@link ReaderNarrationContinuationPlanner}.</li>
 * </ol>
 */
@Service
public class BuildReaderNarrationContinuationPlanUseCase {

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    private final ReaderNarrationContinuationPlanner continuationPlanner;

    public BuildReaderNarrationContinuationPlanUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort,
            ReaderNarrationContinuationPlanner continuationPlanner
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(chapterRepositoryPort, "chapterRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
        this.continuationPlanner = Objects.requireNonNull(continuationPlanner, "continuationPlanner must not be null");
    }

    /**
     * Builds a fresh reader narration continuation plan for the given command.
     *
     * @param command input command containing chapterId, requestedSegmentId, and managedVoiceId
     * @return fresh prioritized continuation plan
     */
    @Transactional(readOnly = true)
    public ReaderNarrationContinuationPlan execute(BuildReaderNarrationContinuationPlanCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.chapterId(), command.requestedSegmentId(), command.managedVoiceId());
    }

    /**
     * Builds a fresh reader narration continuation plan for the given IDs.
     *
     * @param chapterId          the chapter identity
     * @param requestedSegmentId the requested segment identity
     * @param managedVoiceId     the managed voice identity
     * @return fresh prioritized continuation plan
     */
    @Transactional(readOnly = true)
    public ReaderNarrationContinuationPlan execute(UUID chapterId, UUID requestedSegmentId, UUID managedVoiceId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (requestedSegmentId == null) {
            throw new IllegalArgumentException("requestedSegmentId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        // 1. Preflight: Load Chapter and require PUBLISHED status
        Chapter chapter = chapterRepositoryPort.findById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));
        if (chapter.getStatus() != ChapterStatus.PUBLISHED) {
            throw new IllegalStateException(
                    "Chỉ có thể tạo narration continuation plan cho chapter ở trạng thái PUBLISHED. Trạng thái hiện tại: " + chapter.getStatus()
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

        // 3. Preflight: Load requested ChapterNarrationSegment and validate
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

        // 4. Load all CURRENT narration segments for chapterId
        List<ChapterNarrationSegment> currentSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                chapterId,
                ChapterNarrationSegmentStatus.CURRENT
        );

        List<UUID> currentSegmentIds = currentSegments.stream()
                .map(ChapterNarrationSegment::getId)
                .toList();

        // 5. Batch-load audio assignments and failure records (Zero N+1)
        Map<UUID, ChapterNarrationAudio> audioBySegmentId = audioRepositoryPort
                .findBySegmentIdInAndManagedVoiceId(currentSegmentIds, managedVoiceId)
                .stream()
                .collect(Collectors.toMap(ChapterNarrationAudio::getSegmentId, a -> a, (a1, a2) -> a1));

        Map<UUID, ChapterNarrationAudioFailure> failureBySegmentId = failureRepositoryPort
                .findBySegmentIdInAndManagedVoiceId(currentSegmentIds, managedVoiceId)
                .stream()
                .collect(Collectors.toMap(ChapterNarrationAudioFailure::getSegmentId, f -> f, (f1, f2) -> f1));

        long currentVoiceRevision = voice.getSynthesisRevision();

        // 6. Build CURRENT segment snapshots using fresh health resolution
        List<ReaderNarrationContinuationSegmentSnapshot> snapshots = new ArrayList<>(currentSegments.size());
        for (ChapterNarrationSegment segment : currentSegments) {
            ChapterNarrationAudio audio = audioBySegmentId.get(segment.getId());
            ChapterNarrationAudioFailure failure = failureBySegmentId.get(segment.getId());

            ChapterNarrationAudioHealthResolution resolution =
                    ChapterNarrationAudioHealthResolver.resolve(audio, failure, currentVoiceRevision);

            snapshots.add(new ReaderNarrationContinuationSegmentSnapshot(
                    segment.getId(),
                    segment.getSegmentIndex(),
                    resolution.status()
            ));
        }

        // 7. Delegate plan creation, bucketing, and priority ordering to C1 planner
        return continuationPlanner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);
    }
}
