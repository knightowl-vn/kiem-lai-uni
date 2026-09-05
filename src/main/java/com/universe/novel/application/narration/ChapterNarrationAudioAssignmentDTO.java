package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.ChapterNarrationAudio;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Passive DTO representing a narration audio assignment with cache compatibility status.
 */
public record ChapterNarrationAudioAssignmentDTO(
        UUID id,
        UUID segmentId,
        UUID managedVoiceId,
        UUID mediaAssetId,
        long generatedSynthesisRevision,
        boolean compatible,
        Instant createdAt,
        Instant updatedAt
) {

    public static ChapterNarrationAudioAssignmentDTO from(ChapterNarrationAudio audio, boolean compatible) {
        Objects.requireNonNull(audio, "audio must not be null");
        return new ChapterNarrationAudioAssignmentDTO(
                audio.getId(),
                audio.getSegmentId(),
                audio.getManagedVoiceId(),
                audio.getMediaAssetId(),
                audio.getGeneratedSynthesisRevision(),
                compatible,
                audio.getCreatedAt(),
                audio.getUpdatedAt()
        );
    }
}
