package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound repository port for persisting and querying {@link ChapterNarrationAudioFailure} aggregates.
 */
public interface ChapterNarrationAudioFailureRepositoryPort {

    /**
     * Finds the latest failure record for a segment and managed voice pair.
     */
    Optional<ChapterNarrationAudioFailure> findBySegmentIdAndManagedVoiceId(UUID segmentId, UUID managedVoiceId);

    /**
     * Batch-finds failure records for the specified segment IDs and managed voice ID.
     *
     * @param segmentIds     the collection of segment IDs
     * @param managedVoiceId the managed voice ID
     * @return list of matching failure records
     */
    List<ChapterNarrationAudioFailure> findBySegmentIdInAndManagedVoiceId(Collection<UUID> segmentIds, UUID managedVoiceId);

    /**
     * Saves or updates a failure record.
     */
    ChapterNarrationAudioFailure save(ChapterNarrationAudioFailure failure);

    /**
     * Deletes the failure record for a segment and managed voice pair upon successful audio generation/regeneration.
     */
    void deleteBySegmentIdAndManagedVoiceId(UUID segmentId, UUID managedVoiceId);
}
