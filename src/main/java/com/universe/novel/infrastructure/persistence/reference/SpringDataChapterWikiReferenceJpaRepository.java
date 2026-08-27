package com.universe.novel.infrastructure.persistence.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataChapterWikiReferenceJpaRepository
        extends JpaRepository<ChapterWikiReferenceJpaEntity, String> {

    List<ChapterWikiReferenceJpaEntity> findByChapterIdOrderByNormalizedTermAscOccurrenceIndexAsc(String chapterId);

    Optional<ChapterWikiReferenceJpaEntity> findByChapterIdAndNormalizedTermAndOccurrenceIndex(
            String chapterId,
            String normalizedTerm,
            int occurrenceIndex
    );

    List<ChapterWikiReferenceJpaEntity> findByChapterIdAndNormalizedTerm(
            String chapterId,
            String normalizedTerm
    );

    boolean existsByChapterIdAndNormalizedTermAndOccurrenceIndex(
            String chapterId,
            String normalizedTerm,
            int occurrenceIndex
    );

    void deleteAllByChapterId(String chapterId);
}
