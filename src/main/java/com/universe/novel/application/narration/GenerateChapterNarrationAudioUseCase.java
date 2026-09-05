package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
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
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
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
 *             <li>If persistence fails, compensates by deleting the newly created Media asset.</li>
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

    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final TtsProviderPort ttsProviderPort;
    private final MediaContract mediaContract;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public GenerateChapterNarrationAudioUseCase(
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            TtsProviderPort ttsProviderPort,
            MediaContract mediaContract,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.ttsProviderPort = Objects.requireNonNull(ttsProviderPort, "ttsProviderPort must not be null");
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
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

        // 4. Generate audio via TTS provider (outside DB transaction)
        TtsSynthesisResult ttsResult = ttsProviderPort.synthesize(
                new TtsSynthesisCommand(segment.getText(), voice.getProviderVoiceId())
        );

        // 5. Upload audio to Media Platform (outside DB transaction)
        String originalFilename = resolveAudioFilename(segmentId, ttsResult.mediaType());
        byte[] audioBytes = ttsResult.audioBytes();
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
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read audio byte stream for media upload", e);
        }

        // 6. Persist ChapterNarrationAudio assignment with compensation on failure
        Instant now = clockPort.now();
        UUID assignmentId = idGeneratorPort.generate();
        ChapterNarrationAudio newAudio = ChapterNarrationAudio.create(
                assignmentId,
                segmentId,
                managedVoiceId,
                mediaAssetId,
                voice.getSynthesisRevision(),
                now
        );

        ChapterNarrationAudio savedAudio;
        try {
            savedAudio = audioRepositoryPort.save(newAudio);
        } catch (RuntimeException persistenceEx) {
            try {
                mediaContract.delete(mediaAssetId);
            } catch (RuntimeException compEx) {
                persistenceEx.addSuppressed(compEx);
            }
            throw persistenceEx;
        }

        return new GenerateChapterNarrationAudioResult(
                savedAudio.getId(),
                segmentId,
                managedVoiceId,
                savedAudio.getMediaAssetId(),
                savedAudio.getGeneratedSynthesisRevision(),
                NarrationAudioGenerationOutcome.GENERATED
        );
    }

    static String resolveAudioFilename(UUID segmentId, String mediaType) {
        String extension = resolveAudioExtension(mediaType);
        return "segment-" + segmentId + extension;
    }

    static String resolveAudioExtension(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return ".audio";
        }
        String cleanMime = mediaType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        return switch (cleanMime) {
            case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/ogg", "audio/opus" -> ".ogg";
            case "audio/webm" -> ".webm";
            case "audio/aac" -> ".aac";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a";
            case "audio/flac", "audio/x-flac" -> ".flac";
            default -> {
                if (cleanMime.startsWith("audio/")) {
                    String sub = cleanMime.substring("audio/".length()).trim();
                    if (sub.startsWith("x-")) {
                        sub = sub.substring(2);
                    }
                    if (sub.matches("^[a-zA-Z0-9]{2,10}$")) {
                        yield "." + sub;
                    }
                }
                yield ".audio";
            }
        };
    }
}
