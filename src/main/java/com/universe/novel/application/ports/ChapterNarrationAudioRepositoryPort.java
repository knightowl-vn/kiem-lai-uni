package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ChapterNarrationAudio;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application port for persisting and querying {@link ChapterNarrationAudio} assignments.
 */
public interface ChapterNarrationAudioRepositoryPort {

    /**
     * Finds an audio assignment by its unique identity.
     *
     * @param id the assignment identity
     * @return an optional containing the audio assignment if found
     */
    Optional<ChapterNarrationAudio> findById(UUID id);

    /**
     * Finds the audio assignment for a specific segment and managed voice pair.
     *
     * @param segmentId      the narration segment ID
     * @param managedVoiceId the managed voice ID
     * @return an optional containing the audio assignment if found
     */
    Optional<ChapterNarrationAudio> findBySegmentIdAndManagedVoiceId(UUID segmentId, UUID managedVoiceId);

    /**
     * Finds all audio assignments associated with a segment.
     *
     * @param segmentId the narration segment ID
     * @return list of audio assignments for the segment
     */
    List<ChapterNarrationAudio> findBySegmentId(UUID segmentId);

    /**
     * Batch-finds audio assignments for the specified segment IDs and managed voice ID.
     *
     * @param segmentIds     the collection of segment IDs
     * @param managedVoiceId the managed voice ID
     * @return list of matching audio assignments
     */
    List<ChapterNarrationAudio> findBySegmentIdInAndManagedVoiceId(Collection<UUID> segmentIds, UUID managedVoiceId);

    /**
     * Saves a single narration audio assignment.
     *
     * @param audio the narration audio assignment to save
     * @return the saved audio assignment
     */
    ChapterNarrationAudio save(ChapterNarrationAudio audio);

    /**
     * Saves a list of narration audio assignments.
     *
     * @param audios the narration audio assignments to save
     * @return the saved audio assignments
     */
    List<ChapterNarrationAudio> saveAll(List<ChapterNarrationAudio> audios);
}
