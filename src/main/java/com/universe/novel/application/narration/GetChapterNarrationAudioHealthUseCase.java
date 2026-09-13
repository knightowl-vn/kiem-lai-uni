package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ManagedVoice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read use case that dynamically derives the health of narration audio for a segment and managed voice pair (MS-04.9H.8C2).
 * <p>
 * <strong>Locked Health Status Derivation Rules:</strong>
 * <ul>
 *     <li>{@link ChapterNarrationAudioHealthStatus#READY}: Audio exists and its generated synthesis revision matches current voice revision (A == R). Compatible assignment supersedes any failure diagnostic.</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#OUTDATED}: Audio exists but its generated synthesis revision differs from current voice revision (A != R). Failure diagnostic is relevant only if regeneration failed for current voice revision (F == R).</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#FAILED}: No audio exists and a failure exists with F == R.</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#MISSING}: No audio exists and no failure relevant to R; older/non-current failures are suppressed.</li>
 * </ul>
 * <p>
 * Requires the chapter narration segment to exist and be in {@code CURRENT} status.
 * Requires the managed voice to exist. Inspecting health does NOT require the voice to be in {@code ACTIVE} status.
 */
@Service
@Transactional(readOnly = true)
public class GetChapterNarrationAudioHealthUseCase {

    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    public GetChapterNarrationAudioHealthUseCase(
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort
    ) {
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
    }

    /**
     * Executes the health query for the given query command.
     */
    public GetChapterNarrationAudioHealthResult execute(GetChapterNarrationAudioHealthQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        return execute(query.segmentId(), query.managedVoiceId());
    }

    /**
     * Executes the health query for the given segment ID and managed voice ID.
     */
    public GetChapterNarrationAudioHealthResult execute(UUID segmentId, UUID managedVoiceId) {
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        // 1. Validate segment (must exist and be CURRENT)
        ChapterNarrationSegment segment = segmentRepositoryPort.findById(segmentId)
                .orElseThrow(() -> new ChapterNarrationSegmentNotFoundException(segmentId));

        if (!segment.isCurrent()) {
            throw new ChapterNarrationSegmentInvalidStateException(
                    "Chapter narration segment is not in CURRENT status: " + segment.getStatus()
            );
        }

        // 2. Validate managed voice (must exist; does NOT require ACTIVE status)
        ManagedVoice voice = managedVoiceRepositoryPort.findById(managedVoiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(managedVoiceId));

        // 3. Load optional assignment and optional failure record
        Optional<ChapterNarrationAudio> audioOpt =
                audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);

        Optional<ChapterNarrationAudioFailure> failureOpt =
                failureRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId);

        long currentVoiceRevision = voice.getSynthesisRevision();

        // 4. Resolve health status and relevant failure diagnostic via central resolver
        ChapterNarrationAudioHealthResolution resolution =
                ChapterNarrationAudioHealthResolver.resolveFromOptionals(audioOpt, failureOpt, currentVoiceRevision);

        NarrationAudioFailureDiagnosticsDTO failureDto = resolution.relevantFailure() != null
                ? new NarrationAudioFailureDiagnosticsDTO(
                        resolution.relevantFailure().getOperation(),
                        resolution.relevantFailure().getStage(),
                        resolution.relevantFailure().getAttemptedSynthesisRevision(),
                        resolution.relevantFailure().getFailureCount(),
                        resolution.relevantFailure().getErrorType(),
                        resolution.relevantFailure().getErrorMessage(),
                        resolution.relevantFailure().getFirstFailedAt(),
                        resolution.relevantFailure().getLastFailedAt()
                )
                : null;

        if (audioOpt.isPresent()) {
            ChapterNarrationAudio audio = audioOpt.get();
            return new GetChapterNarrationAudioHealthResult(
                    segmentId,
                    managedVoiceId,
                    resolution.status(),
                    audio.getId(),
                    audio.getMediaAssetId(),
                    audio.getGeneratedSynthesisRevision(),
                    currentVoiceRevision,
                    failureDto
            );
        }

        return new GetChapterNarrationAudioHealthResult(
                segmentId,
                managedVoiceId,
                resolution.status(),
                null,
                null,
                null,
                currentVoiceRevision,
                failureDto
        );
    }
}
