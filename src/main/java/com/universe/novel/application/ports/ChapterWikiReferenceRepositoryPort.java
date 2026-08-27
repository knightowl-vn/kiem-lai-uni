package com.universe.novel.application.ports;

import com.universe.novel.domain.reference.ChapterWikiReference;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port quản lý lưu trữ và truy xuất các liên kết tham chiếu Wiki của Chapter.
 */
public interface ChapterWikiReferenceRepositoryPort {

    Optional<ChapterWikiReference> findById(UUID id);

    Optional<ChapterWikiReference> findByChapterIdAndNormalizedTermAndOccurrenceIndex(
            UUID chapterId,
            String normalizedTerm,
            int occurrenceIndex
    );

    List<ChapterWikiReference> findByChapterId(UUID chapterId);

    List<ChapterWikiReference> findByChapterIdAndNormalizedTerm(
            UUID chapterId,
            String normalizedTerm
    );

    boolean existsByChapterIdAndNormalizedTermAndOccurrenceIndex(
            UUID chapterId,
            String normalizedTerm,
            int occurrenceIndex
    );

    ChapterWikiReference save(ChapterWikiReference reference);

    void delete(ChapterWikiReference reference);

    void deleteAllByChapterId(UUID chapterId);
}
