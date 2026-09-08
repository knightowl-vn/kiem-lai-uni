package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application use case that performs an atomic DB handoff for an obsolete narration audio assignment
 * attached to a RETIRED ChapterNarrationSegment (MS-04.9H.7C1C1).
 * <p>
 * <strong>Execution Flow (Atomic DB Transaction):</strong>
 * <ol>
 *     <li>Validates {@code narrationAudioId}.</li>
 *     <li>Loads {@link ChapterNarrationAudio}; if already absent, returns {@link HandoffRetiredNarrationAudioCleanupOutcome#ALREADY_ABSENT}.</li>
 *     <li>Locks the owning {@link ChapterNarrationSegment} row with {@code PESSIMISTIC_WRITE}.</li>
 *     <li>Verifies that the locked segment exists and is in {@code RETIRED} status; if not, returns {@link HandoffRetiredNarrationAudioCleanupOutcome#SKIPPED_NOT_RETIRED}.</li>
 *     <li>Verifies that the Media asset is not referenced by any other {@link ChapterNarrationAudio} assignment; if shared, returns {@link HandoffRetiredNarrationAudioCleanupOutcome#SKIPPED_SHARED_MEDIA_REFERENCE}.</li>
 *     <li>Enqueues a durable cleanup task with reason {@link NarrationMediaCleanupReason#OBSOLETE_RETIRED_SEGMENT_AUDIO}.</li>
 *     <li>Deletes the obsolete {@link ChapterNarrationAudio} assignment row.</li>
 *     <li>Commits both changes atomically and returns {@link HandoffRetiredNarrationAudioCleanupOutcome#HANDED_OFF}.</li>
 * </ol>
 * <p>
 * <strong>Safety Invariant:</strong> No external Media or TTS calls are made in this use case.
 * Physical binary cleanup is handled asynchronously by the background cleanup processor after this transaction commits.
 */
@Service
@Transactional
public class HandoffRetiredNarrationAudioCleanupUseCase {

    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final EnqueueNarrationMediaCleanupUseCase enqueueCleanupUseCase;

    public HandoffRetiredNarrationAudioCleanupUseCase(
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            EnqueueNarrationMediaCleanupUseCase enqueueCleanupUseCase
    ) {
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.enqueueCleanupUseCase = Objects.requireNonNull(enqueueCleanupUseCase, "enqueueCleanupUseCase must not be null");
    }

    /**
     * Executes the atomic cleanup handoff for the given command.
     */
    public HandoffRetiredNarrationAudioCleanupResult execute(HandoffRetiredNarrationAudioCleanupCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.narrationAudioId());
    }

    /**
     * Executes the atomic cleanup handoff for the specified narration audio assignment ID.
     */
    public HandoffRetiredNarrationAudioCleanupResult execute(UUID narrationAudioId) {
        if (narrationAudioId == null) {
            throw new IllegalArgumentException("narrationAudioId must not be null");
        }

        // 1. Load ChapterNarrationAudio assignment
        Optional<ChapterNarrationAudio> audioOpt = audioRepositoryPort.findById(narrationAudioId);
        if (audioOpt.isEmpty()) {
            return new HandoffRetiredNarrationAudioCleanupResult(
                    narrationAudioId,
                    null,
                    null,
                    HandoffRetiredNarrationAudioCleanupOutcome.ALREADY_ABSENT
            );
        }

        ChapterNarrationAudio audio = audioOpt.get();
        UUID segmentId = audio.getSegmentId();
        UUID mediaAssetId = audio.getMediaAssetId();

        // 2. Lock the owning ChapterNarrationSegment row with PESSIMISTIC_WRITE
        ChapterNarrationSegment lockedSegment = segmentRepositoryPort.findByIdForUpdate(segmentId)
                .orElse(null);

        // 3. Verify the locked segment exists and is RETIRED
        if (lockedSegment == null || !lockedSegment.isRetired()) {
            return new HandoffRetiredNarrationAudioCleanupResult(
                    narrationAudioId,
                    segmentId,
                    mediaAssetId,
                    HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_NOT_RETIRED
            );
        }

        // 4. Verify Media asset is not referenced by another ChapterNarrationAudio assignment
        boolean hasOtherReference = audioRepositoryPort.existsOtherReferenceToMediaAsset(mediaAssetId, narrationAudioId);
        if (hasOtherReference) {
            return new HandoffRetiredNarrationAudioCleanupResult(
                    narrationAudioId,
                    segmentId,
                    mediaAssetId,
                    HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_SHARED_MEDIA_REFERENCE
            );
        }

        // 5. Enqueue durable cleanup intent
        enqueueCleanupUseCase.execute(mediaAssetId, NarrationMediaCleanupReason.OBSOLETE_RETIRED_SEGMENT_AUDIO);

        // 6. Delete the ChapterNarrationAudio assignment
        audioRepositoryPort.deleteById(narrationAudioId);

        // 7. Return HANDED_OFF (commits atomically upon method completion)
        return new HandoffRetiredNarrationAudioCleanupResult(
                narrationAudioId,
                segmentId,
                mediaAssetId,
                HandoffRetiredNarrationAudioCleanupOutcome.HANDED_OFF
        );
    }
}
