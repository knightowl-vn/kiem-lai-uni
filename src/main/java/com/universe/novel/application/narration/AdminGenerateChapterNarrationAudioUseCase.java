package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Application use case for Admin-initiated narration audio generation with chapter ownership validation (MS-04.9H.6B).
 */
@Service
public class AdminGenerateChapterNarrationAudioUseCase {

    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final GenerateChapterNarrationAudioUseCase generateUseCase;

    public AdminGenerateChapterNarrationAudioUseCase(
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            GenerateChapterNarrationAudioUseCase generateUseCase
    ) {
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.generateUseCase = Objects.requireNonNull(generateUseCase, "generateUseCase must not be null");
    }

    public GenerateChapterNarrationAudioResult execute(UUID chapterId, UUID segmentId, UUID managedVoiceId) {
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

        return generateUseCase.execute(segmentId, managedVoiceId);
    }
}
