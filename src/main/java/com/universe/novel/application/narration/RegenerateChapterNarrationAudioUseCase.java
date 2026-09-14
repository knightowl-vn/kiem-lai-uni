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
import com.universe.novel.application.ports.SegmentAudioEncoderPort;
import com.universe.novel.application.ports.TtsProviderPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case orchestrating safe regeneration of an existing stale or legacy ChapterNarrationAudio assignment
 * with canonical MP3 encoding and encoded timing metadata.
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Loads {@link ChapterNarrationSegment} and validates that it exists and is in {@code CURRENT} status.</li>
 *     <li>Loads {@link ManagedVoice} and validates that it exists and is in {@code ACTIVE} status.</li>
 *     <li>Loads existing {@link ChapterNarrationAudio} assignment (rejecting with {@link ChapterNarrationAudioNotFoundException} if missing).</li>
 *     <li>If the assignment is already canonical (compatible with current voice synthesis revision AND has canonical encoded timing at 48 kHz), returns {@link RegenerateNarrationAudioOutcome#ALREADY_CURRENT} without TTS, encoder, or Media writes.</li>
 *     <li>If the assignment is stale, lacks encoded timing (legacy WAV), or has non-canonical timing:
 *         <ul>
 *             <li>Synthesizes replacement audio via {@link TtsProviderPort}.</li>
 *             <li>Normalizes WAV boundary via {@link NarrationWavBoundaryNormalizer} and encodes to canonical MP3 via {@link SegmentAudioEncoderPort}.</li>
 *             <li>Uploads new canonical encoded audio to the Media platform via {@link MediaContract}.</li>
 *             <li>Updates assignment via {@link ChapterNarrationAudio#replaceSuccessfulAudio(UUID, long, Long, Integer, Instant)} with encoded timing and persists the same assignment identity.</li>
 *             <li>If persistence fails, initiates durable cleanup handoff for the newly uploaded unreferenced Media asset via {@link RequestNarrationMediaCleanupUseCase} and restores in-memory domain state, preserving the OLD Media asset.</li>
 *             <li>Records failure diagnostics on any error stage without masking the primary exception.</li>
 *             <li>Only after persistence succeeds, initiates durable cleanup handoff for the superseded OLD Media asset via {@link RequestNarrationMediaCleanupUseCase}.</li>
 *             <li>Clears existing failure diagnostics on success.</li>
 *             <li>If old asset cleanup fails completely, propagates the cleanup request exception without rolling back the persisted new assignment.</li>
 *             <li>Returns {@link RegenerateNarrationAudioOutcome#REGENERATED}.</li>
 *         </ul>
 *     </li>
 * </ol>
 * <p>
 * <strong>Transaction Boundary:</strong> External HTTP TTS synthesis, audio encoding, and Media binary upload occur outside
 * of active database transactions. The assignment switch is a short persistence operation.
 */
@Service
public class RegenerateChapterNarrationAudioUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegenerateChapterNarrationAudioUseCase.class);
    private static final int CANONICAL_SAMPLE_RATE_HZ = 48000;

    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    private final TtsProviderPort ttsProviderPort;
    private final SegmentAudioEncoderPort segmentAudioEncoderPort;
    private final MediaContract mediaContract;
    private final RequestNarrationMediaCleanupUseCase cleanupRequestUseCase;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public RegenerateChapterNarrationAudioUseCase(
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort,
            TtsProviderPort ttsProviderPort,
            SegmentAudioEncoderPort segmentAudioEncoderPort,
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
        this.segmentAudioEncoderPort = Objects.requireNonNull(segmentAudioEncoderPort, "segmentAudioEncoderPort must not be null");
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
        this.cleanupRequestUseCase = Objects.requireNonNull(cleanupRequestUseCase, "cleanupRequestUseCase must not be null");
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

        // 4. Check if already current: requires revision match AND canonical timing (48 kHz, contributionSamples > 0)
        if (isCanonicalCurrent(audio, voice.getSynthesisRevision())) {
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

        // 5. Stale or legacy assignment: capture EXACT old state snapshot for compensation and cleanup
        UUID oldMediaAssetId = audio.getMediaAssetId();
        long oldGeneratedSynthesisRevision = audio.getGeneratedSynthesisRevision();
        Long oldEncodedContributionSamples = audio.getEncodedContributionSamples();
        Integer oldEncodedSampleRateHz = audio.getEncodedSampleRateHz();
        Instant oldUpdatedAt = audio.getUpdatedAt();
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

        // 7. Normalize WAV boundary and encode audio via SegmentAudioEncoderPort (outside DB transaction)
        byte[] normalizedBytes = NarrationWavBoundaryNormalizer.normalize(ttsResult.audioBytes(), ttsResult.mediaType());
        SegmentAudioEncodingResult encodedResult;
        try {
            encodedResult = segmentAudioEncoderPort.encode(
                    SegmentAudioEncodingRequest.of(ttsResult.mediaType(), normalizedBytes)
            );
        } catch (RuntimeException encodeEx) {
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.REGENERATION,
                    NarrationAudioFailureStage.AUDIO_ENCODING, attemptedRevision, encodeEx, encodeEx);
            throw encodeEx;
        }

        // 8. Upload canonical encoded audio to Media Platform (outside DB transaction)
        UUID newMediaAssetId;
        long encodedContributionSamples;
        int encodedSampleRateHz;
        InputStream inputStream = null;

        // 8a. Pre-upload metadata preparation and stream opening (AUDIO_ENCODING failure stage)
        UploadMediaAssetRequestDTO uploadRequest;
        try {
            encodedContributionSamples = encodedResult.encodedContributionSamples();
            encodedSampleRateHz = encodedResult.sampleRateHz();
            long sizeBytes = encodedResult.sizeBytes();
            String mimeType = encodedResult.mimeType();
            String originalFilename = NarrationAudioFilenameResolver.resolveFilename(segmentId, mimeType);
            inputStream = encodedResult.openStream();
            uploadRequest = new UploadMediaAssetRequestDTO(
                    inputStream,
                    sizeBytes,
                    mimeType,
                    MediaTypeDTO.AUDIO,
                    MediaVisibilityDTO.PUBLIC,
                    originalFilename
            );
        } catch (RuntimeException preUploadEx) {
            closeWithSuppression(inputStream, preUploadEx);
            closeWithSuppression(encodedResult, preUploadEx);
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.REGENERATION,
                    NarrationAudioFailureStage.AUDIO_ENCODING, attemptedRevision, preUploadEx, preUploadEx);
            throw preUploadEx;
        }

        // 8b. Media binary upload (MEDIA_UPLOAD failure stage)
        try {
            UploadMediaAssetResponseDTO uploadResponse = mediaContract.uploadAsset(uploadRequest);
            if (uploadResponse == null || uploadResponse.assetId() == null) {
                throw new IllegalStateException("Media upload returned null response or asset ID");
            }
            newMediaAssetId = uploadResponse.assetId();
        } catch (RuntimeException uploadEx) {
            closeWithSuppression(inputStream, uploadEx);
            closeWithSuppression(encodedResult, uploadEx);
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.REGENERATION,
                    NarrationAudioFailureStage.MEDIA_UPLOAD, attemptedRevision, uploadEx, uploadEx);
            throw uploadEx;
        }

        // 8c. Post-upload local resource cleanup (log warnings only, never fail upload or request media cleanup)
        closeSafely(inputStream, "encoded audio input stream");
        closeSafely(encodedResult, "encoded segment audio result");

        // 9. Post-upload assignment switch and persistence within unified cleanup boundary
        ChapterNarrationAudio savedAudio;
        try {
            Instant now = clockPort.now();
            audio.replaceSuccessfulAudio(
                    newMediaAssetId,
                    attemptedRevision,
                    encodedContributionSamples,
                    encodedSampleRateHz,
                    now
            );
            savedAudio = audioRepositoryPort.save(audio);
        } catch (RuntimeException assignmentEx) {
            // Revert in-memory domain state to exact previous state
            try {
                audio.replaceSuccessfulAudio(
                        oldMediaAssetId,
                        oldGeneratedSynthesisRevision,
                        oldEncodedContributionSamples,
                        oldEncodedSampleRateHz,
                        oldUpdatedAt
                );
            } catch (RuntimeException restoreEx) {
                log.warn("Failed to restore in-memory ChapterNarrationAudio state for segment [{}] and voice [{}]: {}",
                        segmentId, managedVoiceId, restoreEx.getMessage(), restoreEx);
                assignmentEx.addSuppressed(restoreEx);
            }

            // 1. Request durable cleanup for this request's unreferenced new media asset
            RuntimeException cleanupEx = null;
            try {
                cleanupRequestUseCase.execute(newMediaAssetId, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
            } catch (RuntimeException ex) {
                cleanupEx = ex;
                log.warn("Failed to request durable cleanup for unreferenced Media asset [{}]: {}",
                        newMediaAssetId, ex.getMessage(), ex);
            }

            // 2. Concurrency optimistic-lock race handling
            if (isOptimisticLockConflict(assignmentEx)) {
                Optional<ChapterNarrationAudio> winnerOpt = Optional.empty();
                RuntimeException winnerLookupEx = null;
                try {
                    winnerOpt = audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);
                } catch (RuntimeException lookupEx) {
                    winnerLookupEx = lookupEx;
                    log.warn("Failed to reload candidate winner for segment [{}] and voice [{}] after optimistic race: {}",
                            segmentId, managedVoiceId, lookupEx.getMessage(), lookupEx);
                }

                if (winnerLookupEx == null && winnerOpt.isPresent()) {
                    ChapterNarrationAudio winner = winnerOpt.get();
                    if (isCanonicalCurrent(winner, attemptedRevision)) {
                        if (cleanupEx != null) {
                            cleanupEx.addSuppressed(assignmentEx);
                            throw cleanupEx;
                        }
                        return new RegenerateChapterNarrationAudioResult(
                                winner.getId(),
                                segmentId,
                                managedVoiceId,
                                oldMediaAssetId,
                                winner.getMediaAssetId(),
                                winner.getGeneratedSynthesisRevision(),
                                RegenerateNarrationAudioOutcome.REGENERATED
                        );
                    }
                }

                if (winnerLookupEx != null) {
                    assignmentEx.addSuppressed(winnerLookupEx);
                }
            }

            if (cleanupEx != null) {
                assignmentEx.addSuppressed(cleanupEx);
            }
            recordFailureSafely(segmentId, managedVoiceId, NarrationAudioOperation.REGENERATION,
                    NarrationAudioFailureStage.ASSIGNMENT_PERSISTENCE, attemptedRevision, assignmentEx, assignmentEx);
            throw assignmentEx;
        }

        // 10. After successful persistence, clear any unresolved failure record superseded by successful regeneration
        clearFailureSafely(segmentId, managedVoiceId, savedAudio.getGeneratedSynthesisRevision());

        // 11. Retire/cleanup OLD superseded Media asset
        cleanupRequestUseCase.execute(oldMediaAssetId, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);

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

    private boolean isOptimisticLockConflict(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof OptimisticLockingFailureException
                    || current instanceof OptimisticLockException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isCanonicalCurrent(ChapterNarrationAudio audio, long currentVoiceRevision) {
        return audio != null
                && audio.isCompatibleWith(currentVoiceRevision)
                && audio.hasEncodedTiming()
                && audio.getEncodedContributionSamples() != null
                && audio.getEncodedContributionSamples() > 0L
                && audio.getEncodedSampleRateHz() != null
                && audio.getEncodedSampleRateHz() == CANONICAL_SAMPLE_RATE_HZ;
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
            if (primaryException != null && primaryException != diagEx) {
                primaryException.addSuppressed(diagEx);
            }
        }
    }

    private void closeWithSuppression(AutoCloseable closeable, Throwable primaryException) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable closeEx) {
                if (primaryException != null && primaryException != closeEx) {
                    primaryException.addSuppressed(closeEx);
                }
            }
        }
    }

    private void closeSafely(AutoCloseable closeable, String resourceDescription) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ex) {
                log.warn("Failed to close {} after successful media upload: {}", resourceDescription, ex.getMessage(), ex);
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
