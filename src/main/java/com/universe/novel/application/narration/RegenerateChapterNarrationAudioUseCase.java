package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.ChapterNarrationAudioNotFoundException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.TtsProviderPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case orchestrating safe regeneration of an existing stale ChapterNarrationAudio assignment.
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Loads {@link ChapterNarrationSegment} and validates that it exists and is in {@code CURRENT} status.</li>
 *     <li>Loads {@link ManagedVoice} and validates that it exists and is in {@code ACTIVE} status.</li>
 *     <li>Loads existing {@link ChapterNarrationAudio} assignment (rejecting with {@link ChapterNarrationAudioNotFoundException} if missing).</li>
 *     <li>If the assignment is already compatible with current voice synthesis revision, returns {@link RegenerateNarrationAudioOutcome#ALREADY_CURRENT} without TTS or Media writes.</li>
 *     <li>If the assignment is stale:
 *         <ul>
 *             <li>Synthesizes replacement audio via {@link TtsProviderPort}.</li>
 *             <li>Uploads new audio binary to the Media platform via {@link MediaContract}.</li>
 *             <li>Updates assignment via {@link ChapterNarrationAudio#replaceSuccessfulAudio(UUID, long, Instant)} and persists the same assignment identity.</li>
 *             <li>If persistence fails, compensates the NEW Media asset and restores in-memory domain state, preserving the OLD Media asset.</li>
 *             <li>Records failure diagnostics on any error stage without masking the primary exception.</li>
 *             <li>Only after persistence succeeds, retires/deletes the OLD Media asset via {@link MediaContract#delete(UUID)}.</li>
 *             <li>Clears existing failure diagnostics on success.</li>
 *             <li>If old asset cleanup fails, logs the warning without rolling back the new assignment.</li>
 *             <li>Returns {@link RegenerateNarrationAudioOutcome#REGENERATED}.</li>
 *         </ul>
 *     </li>
 * </ol>
 * <p>
 * <strong>Transaction Boundary:</strong> External HTTP TTS synthesis and Media binary upload occur outside
 * of active database transactions. The assignment switch is a short persistence operation.
 */
@Service
public class RegenerateChapterNarrationAudioUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegenerateChapterNarrationAudioUseCase.class);

    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    private final TtsProviderPort ttsProviderPort;
    private final MediaContract mediaContract;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public RegenerateChapterNarrationAudioUseCase(
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort,
            TtsProviderPort ttsProviderPort,
            MediaContract mediaContract,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
        this.ttsProviderPort = Objects.requireNonNull(ttsProviderPort, "ttsProviderPort must not be null");
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "idGeneratorPort must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
    }

    /**
     * Executes the narration audio regeneration flow for the given command.
     *
     * @param command input command containing segmentId and managedVoiceId
     * @return regeneration result record
     */
    public RegenerateChapterNarrationAudioResult execute(RegenerateChapterNarrationAudioCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.segmentId(), command.managedVoiceId());
    }

    /**
     * Executes the narration audio regeneration flow for the given segment ID and managed voice ID.
     *
     * @param segmentId      identity of the chapter narration segment
     * @param managedVoiceId identity of the managed voice
     * @return regeneration result record
     */
    public RegenerateChapterNarrationAudioResult execute(UUID segmentId, UUID managedVoiceId) {
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        // 1. Load segment and validate CURRENT status
        ChapterNarrationSegment segment = segmentRepositoryPort.findById(segmentId)
                .orElseThrow(() -> new ChapterNarrationSegmentNotFoundException(segmentId));

        if (!segment.isCurrent()) {
            throw new ChapterNarrationSegmentInvalidStateException(
                    "Chapter narration segment is not in CURRENT status: " + segment.getStatus()
            );
        }

        // 2. Load managed voice and validate ACTIVE status
        ManagedVoice voice = managedVoiceRepositoryPort.findById(managedVoiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(managedVoiceId));

        if (!voice.isActive()) {
            throw new ManagedVoiceInvalidStateException(
                    "Managed voice is not in ACTIVE status: " + voice.getStatus()
            );
        }

        // 3. Load existing audio assignment
        ChapterNarrationAudio audio = audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId)
                .orElseThrow(() -> new ChapterNarrationAudioNotFoundException(segmentId, managedVoiceId));

        // 4. If already compatible, return ALREADY_CURRENT without mutating or calling TTS/Media
        if (audio.isCompatibleWith(voice.getSynthesisRevision())) {
            return new RegenerateChapterNarrationAudioResult(
                    audio.getId(),
                    segmentId,
                    managedVoiceId,
                    audio.getMediaAssetId(),
                    audio.getMediaAssetId(),
                    audio.getGeneratedSynthesisRevision(),
                    RegenerateNarrationAudioOutcome.ALREADY_CURRENT
            );
        }

        // 5. Stale assignment: capture old state for compensation and cleanup
        UUID oldMediaAssetId = audio.getMediaAssetId();
        long previousRevision = audio.getGeneratedSynthesisRevision();
        Instant previousUpdatedAt = audio.getUpdatedAt();
        long attemptedRevision = voice.getSynthesisRevision();

        // 6. Synthesize replacement audio via TTS provider (outside DB transaction)
        TtsSynthesisResult ttsResult;
        try {
            ttsResult = ttsProviderPort.synthesize(
                    new TtsSynthesisCommand(segment.getText(), voice.getProviderVoiceId())
            );
        } catch (RuntimeException ttsEx) {
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.REGENERATION,
                    NarrationAudioFailureStage.TTS_SYNTHESIS, attemptedRevision, ttsEx, ttsEx);
            throw ttsEx;
        }

        // 7. Upload new audio to Media Platform (outside DB transaction)
        String originalFilename = NarrationAudioFilenameResolver.resolveFilename(segmentId, ttsResult.mediaType());
        byte[] audioBytes = ttsResult.audioBytes();
        UUID newMediaAssetId;

        try (InputStream inputStream = new ByteArrayInputStream(audioBytes)) {
            UploadMediaAssetRequestDTO uploadRequest = new UploadMediaAssetRequestDTO(
                    inputStream,
                    audioBytes.length,
                    ttsResult.mediaType(),
                    MediaTypeDTO.AUDIO,
                    MediaVisibilityDTO.PUBLIC,
                    originalFilename
            );
            UploadMediaAssetResponseDTO uploadResponse = mediaContract.uploadAsset(uploadRequest);
            newMediaAssetId = uploadResponse.assetId();
        } catch (RuntimeException mediaEx) {
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.REGENERATION,
                    NarrationAudioFailureStage.MEDIA_UPLOAD, attemptedRevision, mediaEx, mediaEx);
            throw mediaEx;
        } catch (IOException e) {
            IllegalStateException isEx = new IllegalStateException("Failed to read audio byte stream for media upload", e);
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.REGENERATION,
                    NarrationAudioFailureStage.MEDIA_UPLOAD, attemptedRevision, isEx, isEx);
            throw isEx;
        }

        // 8. Apply domain mutation and persist assignment
        Instant now = clockPort.now();
        audio.replaceSuccessfulAudio(newMediaAssetId, attemptedRevision, now);

        ChapterNarrationAudio savedAudio;
        try {
            savedAudio = audioRepositoryPort.save(audio);
        } catch (RuntimeException persistenceEx) {
            // Revert in-memory domain state to previous state so heap object remains un-mutated
            audio.replaceSuccessfulAudio(oldMediaAssetId, previousRevision, previousUpdatedAt);

            // Compensate the newly uploaded media asset
            try {
                mediaContract.delete(newMediaAssetId);
            } catch (RuntimeException compEx) {
                persistenceEx.addSuppressed(compEx);
            }
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.REGENERATION,
                    NarrationAudioFailureStage.ASSIGNMENT_PERSISTENCE, attemptedRevision, persistenceEx, persistenceEx);
            throw persistenceEx;
        }

        // 9. After successful persistence, clear any unresolved failure record
        clearFailureSafely(segmentId, managedVoiceId);

        // 10. Retire/delete OLD Media asset
        try {
            mediaContract.delete(oldMediaAssetId);
        } catch (RuntimeException cleanupEx) {
            log.warn(
                    "Failed to delete old Media asset [{}] after regenerating audio for segment [{}]: {}",
                    oldMediaAssetId, segmentId, cleanupEx.getMessage(), cleanupEx
            );
        }

        return new RegenerateChapterNarrationAudioResult(
                savedAudio.getId(),
                segmentId,
                managedVoiceId,
                oldMediaAssetId,
                newMediaAssetId,
                savedAudio.getGeneratedSynthesisRevision(),
                RegenerateNarrationAudioOutcome.REGENERATED
        );
    }

    private void recordFailureSafely(
            UUID segmentId,
            UUID managedVoiceId,
            NarrationAudioOperation operation,
            NarrationAudioFailureStage stage,
            long attemptedRevision,
            Throwable throwable,
            Exception primaryException
    ) {
        try {
            Instant now = clockPort.now();
            String errorType = ChapterNarrationAudioFailure.sanitizeErrorType(throwable);

            Optional<ChapterNarrationAudioFailure> existingOpt =
                    failureRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);

            if (existingOpt.isPresent()) {
                ChapterNarrationAudioFailure existing = existingOpt.get();
                existing.recordFailure(operation, stage, attemptedRevision, errorType, now);
                failureRepositoryPort.save(existing);
            } else {
                UUID failureId = idGeneratorPort.generate();
                ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.create(
                        failureId,
                        segmentId,
                        managedVoiceId,
                        operation,
                        stage,
                        attemptedRevision,
                        errorType,
                        now
                );
                failureRepositoryPort.save(failure);
            }
        } catch (RuntimeException diagEx) {
            if (primaryException != null) {
                primaryException.addSuppressed(diagEx);
            }
        }
    }

    private void clearFailureSafely(UUID segmentId, UUID managedVoiceId) {
        try {
            failureRepositoryPort.deleteBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);
        } catch (RuntimeException clearEx) {
            log.warn(
                    "Failed to clear narration audio failure record for segment [{}] and voice [{}]: {}",
                    segmentId, managedVoiceId, clearEx.getMessage(), clearEx
            );
        }
    }
}
