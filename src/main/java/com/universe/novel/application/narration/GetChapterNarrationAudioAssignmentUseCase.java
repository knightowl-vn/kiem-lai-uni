package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application use case to resolve the current narration audio assignment for a given segment and managed voice,
 * exposing whether the audio is compatible with the supplied current synthesis revision.
 */
@Service
@Transactional(readOnly = true)
public class GetChapterNarrationAudioAssignmentUseCase {

    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    public GetChapterNarrationAudioAssignmentUseCase(ChapterNarrationAudioRepositoryPort audioRepositoryPort) {
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
    }

    /**
     * Executes the query using the strongly-typed query object.
     *
     * @param query the query parameters
     * @return an optional containing the passive DTO if an audio assignment exists
     */
    public Optional<ChapterNarrationAudioAssignmentDTO> execute(GetChapterNarrationAudioAssignmentQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return execute(query.segmentId(), query.managedVoiceId(), query.currentSynthesisRevision());
    }

    /**
     * Executes the query using individual arguments.
     *
     * @param segmentId                the segment identity
     * @param managedVoiceId           the managed voice identity
     * @param currentSynthesisRevision the current synthesis revision of the voice
     * @return an optional containing the passive DTO if an audio assignment exists
     */
    public Optional<ChapterNarrationAudioAssignmentDTO> execute(
            UUID segmentId,
            UUID managedVoiceId,
            long currentSynthesisRevision
    ) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        if (currentSynthesisRevision < 1) {
            throw new IllegalArgumentException("currentSynthesisRevision must be at least 1: " + currentSynthesisRevision);
        }

        return audioRepositoryPort.findBySegmentIdAndManagedVoiceId(segmentId, managedVoiceId)
                .map(audio -> ChapterNarrationAudioAssignmentDTO.from(
                        audio,
                        audio.isCompatibleWith(currentSynthesisRevision)
                ));
    }
}
