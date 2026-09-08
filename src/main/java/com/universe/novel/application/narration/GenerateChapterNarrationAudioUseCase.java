package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.ChapterNarrationAudioAlreadyExistsException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.TtsProviderPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
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
 * Use case orchestrating TTS synthesis, Media binary upload, and persistence of chapter narration audio assignments.
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Loads {@link ChapterNarrationSegment} and validates that it exists and is in {@code CURRENT} status.</li>
 *     <li>Loads {@link ManagedVoice} and validates that it exists and is in {@code ACTIVE} status.</li>
 *     <li>Checks for an existing {@link ChapterNarrationAudio} assignment:
 *         <ul>
 *             <li>If present and its synthesis revision matches the voice, returns {@link NarrationAudioGenerationOutcome#REUSED} without invoking TTS or Media.</li>
 *             <li>If present but its synthesis revision is stale, returns {@link NarrationAudioGenerationOutcome#STALE} without regenerating (regeneration is owned by H.5D).</li>
 *         </ul>
 *     </li>
 *     <li>If no assignment exists:
 *         <ul>
 *             <li>Synthesizes audio via {@link TtsProviderPort}.</li>
 *             <li>Uploads synthesized audio to the Media platform via {@link MediaContract}.</li>
 *             <li>Persists a new {@link ChapterNarrationAudio} assignment.</li>
 *             <li>If persistence fails, initiates durable cleanup handoff for the newly uploaded unreferenced Media asset via {@link RequestNarrationMediaCleanupUseCase}.</li>
 *             <li>Records failure diagnostics on any error stage without masking the primary exception.</li>
 *             <li>Clears existing failure diagnostics on success.</li>
 *             <li>Returns {@link NarrationAudioGenerationOutcome#GENERATED}.</li>
 *         </ul>
 *     </li>
 * </ol>
 * <p>
 * <strong>Transaction Boundary:</strong> This service does NOT hold an active database transaction across
 * the HTTP TTS synthesis call or Media binary upload. Database writes occur only after media storage completes.
 */
@Service
public class GenerateChapterNarrationAudioUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateChapterNarrationAudioUseCase.class);

    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    private final TtsProviderPort ttsProviderPort;
    private final MediaContract mediaContract;
    private final RequestNarrationMediaCleanupUseCase cleanupRequestUseCase;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public GenerateChapterNarrationAudioUseCase(
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort,
            TtsProviderPort ttsProviderPort,
            MediaContract mediaContract,
            RequestNarrationMediaCleanupUseCase cleanupRequestUseCase,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
        this.ttsProviderPort = Objects.requireNonNull(ttsProviderPort, "ttsProviderPort must not be null");
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
        this.cleanupRequestUseCase = Objects.requireNonNull(cleanupRequestUseCase, "cleanupRequestUseCase must not be null");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "idGeneratorPort must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
    }

    /**
     * Executes the narration audio generation flow for the given command.
     *
     * @param command input command containing segmentId and managedVoiceId
     * @return generation result record
     */
    public GenerateChapterNarrationAudioResult execute(GenerateChapterNarrationAudioCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.segmentId(), command.managedVoiceId());
    }

    /**
     * Executes the narration audio generation flow for the given segment ID and managed voice ID.
     *
     * @param segmentId      identity of the chapter narration segment
     * @param managedVoiceId identity of the managed voice
     * @return generation result record
     */
    public GenerateChapterNarrationAudioResult execute(UUID segmentId, UUID managedVoiceId) {
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

        // 3. Check for existing audio assignment
        Optional<ChapterNarrationAudio> existingAudioOpt =
                audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);

        if (existingAudioOpt.isPresent()) {
            ChapterNarrationAudio existingAudio = existingAudioOpt.get();
            if (existingAudio.isCompatibleWith(voice.getSynthesisRevision())) {
                return new GenerateChapterNarrationAudioResult(
                        existingAudio.getId(),
                        segmentId,
                        managedVoiceId,
                        existingAudio.getMediaAssetId(),
                        existingAudio.getGeneratedSynthesisRevision(),
                        NarrationAudioGenerationOutcome.REUSED
                );
            } else {
                return new GenerateChapterNarrationAudioResult(
                        existingAudio.getId(),
                        segmentId,
                        managedVoiceId,
                        existingAudio.getMediaAssetId(),
                        existingAudio.getGeneratedSynthesisRevision(),
                        NarrationAudioGenerationOutcome.STALE
                );
            }
        }

        long attemptedRevision = voice.getSynthesisRevision();

        // 4. Generate audio via TTS provider (outside DB transaction)
        TtsSynthesisResult ttsResult;
        try {
            ttsResult = ttsProviderPort.synthesize(
                    new TtsSynthesisCommand(segment.getText(), voice.getProviderVoiceId())
            );
        } catch (RuntimeException ttsEx) {
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.INITIAL_GENERATION,
                    NarrationAudioFailureStage.TTS_SYNTHESIS, attemptedRevision, ttsEx, ttsEx);
            throw ttsEx;
        }

        // 5. Upload audio to Media Platform (outside DB transaction)
        String originalFilename = NarrationAudioFilenameResolver.resolveFilename(segmentId, ttsResult.mediaType());
        byte[] audioBytes = NarrationWavBoundaryNormalizer.normalize(ttsResult.audioBytes(), ttsResult.mediaType());
        UUID mediaAssetId;

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
            mediaAssetId = uploadResponse.assetId();
        } catch (RuntimeException mediaEx) {
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.INITIAL_GENERATION,
                    NarrationAudioFailureStage.MEDIA_UPLOAD, attemptedRevision, mediaEx, mediaEx);
            throw mediaEx;
        } catch (IOException e) {
            IllegalStateException isEx = new IllegalStateException("Failed to read audio byte stream for media upload", e);
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.INITIAL_GENERATION,
                    NarrationAudioFailureStage.MEDIA_UPLOAD, attemptedRevision, isEx, isEx);
            throw isEx;
        }

        // 6. Persist ChapterNarrationAudio assignment with durable cleanup handoff on failure
        Instant now = clockPort.now();
        UUID assignmentId = idGeneratorPort.generate();
        ChapterNarrationAudio newAudio = ChapterNarrationAudio.create(
                assignmentId,
                segmentId,
                managedVoiceId,
                mediaAssetId,
                attemptedRevision,
                now
        );

        ChapterNarrationAudio savedAudio;
        try {
            savedAudio = audioRepositoryPort.save(newAudio);
        } catch (RuntimeException persistenceEx) {
            // 1. Request durable cleanup for this request's unreferenced new media asset
            RuntimeException cleanupEx = null;
            try {
                cleanupRequestUseCase.execute(mediaAssetId, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
            } catch (RuntimeException ex) {
                cleanupEx = ex;
                log.warn("Failed to request durable cleanup for unreferenced Media asset [{}]: {}",
                        mediaAssetId, ex.getMessage(), ex);
            }

            // 2. Concurrency duplicate race handling
            if (isDuplicateRaceConflict(persistenceEx)) {
                Optional<ChapterNarrationAudio> winnerOpt = Optional.empty();
                RuntimeException winnerLookupEx = null;
                try {
                    winnerOpt = audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);
                } catch (RuntimeException lookupEx) {
                    winnerLookupEx = lookupEx;
                    log.warn("Failed to reload candidate winner for segment [{}] and voice [{}] after duplicate race: {}",
                            segmentId, managedVoiceId, lookupEx.getMessage(), lookupEx);
                }

                if (winnerLookupEx == null && winnerOpt.isPresent()) {
                    ChapterNarrationAudio winner = winnerOpt.get();
                    if (winner.isCompatibleWith(attemptedRevision)) {
                        if (cleanupEx != null) {
                            cleanupEx.addSuppressed(persistenceEx);
                            throw cleanupEx;
                        }
                        return new GenerateChapterNarrationAudioResult(
                                winner.getId(),
                                segmentId,
                                managedVoiceId,
                                winner.getMediaAssetId(),
                                winner.getGeneratedSynthesisRevision(),
                                NarrationAudioGenerationOutcome.REUSED
                        );
                    }
                }

                if (winnerLookupEx != null) {
                    persistenceEx.addSuppressed(winnerLookupEx);
                }
            }

            if (cleanupEx != null) {
                persistenceEx.addSuppressed(cleanupEx);
            }
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.INITIAL_GENERATION,
                    NarrationAudioFailureStage.ASSIGNMENT_PERSISTENCE, attemptedRevision, persistenceEx, persistenceEx);
            throw persistenceEx;
        }

        // 7. Clear unresolved failure record superseded by successful generation
        clearFailureSafely(segmentId, managedVoiceId, savedAudio.getGeneratedSynthesisRevision());

        return new GenerateChapterNarrationAudioResult(
                savedAudio.getId(),
                segmentId,
                managedVoiceId,
                savedAudio.getMediaAssetId(),
                savedAudio.getGeneratedSynthesisRevision(),
                NarrationAudioGenerationOutcome.GENERATED
        );
    }

    private boolean isDuplicateRaceConflict(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ChapterNarrationAudioAlreadyExistsException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static String resolveAudioFilename(UUID segmentId, String mediaType) {
        return NarrationAudioFilenameResolver.resolveFilename(segmentId, mediaType);
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

    private void clearFailureSafely(UUID segmentId, UUID managedVoiceId, long successfulRevision) {
        try {
            failureRepositoryPort.deleteSupersededBySuccessfulRevision(segmentId, managedVoiceId, successfulRevision);
        } catch (RuntimeException clearEx) {
            log.warn(
                    "Failed to clear narration audio failure record for segment [{}] and voice [{}] at revision [{}]: {}",
                    segmentId, managedVoiceId, successfulRevision, clearEx.getMessage(), clearEx
            );
        }
    }
}
