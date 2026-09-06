package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Application use case for Admin-initiated narration audio regeneration with chapter ownership validation (MS-04.9H.6B).
 */
@Service
public class AdminRegenerateChapterNarrationAudioUseCase {

    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final RegenerateChapterNarrationAudioUseCase regenerateUseCase;

    public AdminRegenerateChapterNarrationAudioUseCase(
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            RegenerateChapterNarrationAudioUseCase regenerateUseCase
    ) {
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.regenerateUseCase = Objects.requireNonNull(regenerateUseCase, "regenerateUseCase must not be null");
    }

    public RegenerateChapterNarrationAudioResult execute(UUID chapterId, UUID segmentId, UUID managedVoiceId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        ChapterNarrationSegment segment = segmentRepositoryPort.findById(segmentId)
                .orElseThrow(() -> new ChapterNarrationSegmentNotFoundException(segmentId));

        if (!Objects.equals(chapterId, segment.getChapterId())) {
            throw new ChapterNarrationSegmentNotFoundException(segmentId, chapterId);
        }

        return regenerateUseCase.execute(segmentId, managedVoiceId);
    }
}
