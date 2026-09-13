package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound repository port for persisting and querying {@link ChapterNarrationSegment} aggregates.
 */
public interface ChapterNarrationSegmentRepositoryPort {

    /**
     * Finds a single narration segment by ID.
     */
    Optional<ChapterNarrationSegment> findById(UUID id);

    /**
     * Finds a single narration segment by ID with a pessimistic write lock.
     */
    Optional<ChapterNarrationSegment> findByIdForUpdate(UUID id);

    /**
     * Finds all narration segments (both CURRENT and RETIRED) for a chapter, ordered by segmentIndex ASC.
     */
    List<ChapterNarrationSegment> findByChapterId(UUID chapterId);

    /**
     * Finds narration segments for a chapter filtered by status, ordered by segmentIndex ASC.
     */
    List<ChapterNarrationSegment> findByChapterIdAndStatus(UUID chapterId, ChapterNarrationSegmentStatus status);

    /**
     * Saves a single narration segment.
     */
    ChapterNarrationSegment save(ChapterNarrationSegment segment);

    /**
     * Saves all given narration segments.
     */
    List<ChapterNarrationSegment> saveAll(List<ChapterNarrationSegment> segments);
}
