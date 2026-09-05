package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataChapterNarrationSegmentJpaRepository
        extends JpaRepository<ChapterNarrationSegmentJpaEntity, String> {

    List<ChapterNarrationSegmentJpaEntity> findByChapterIdOrderBySegmentIndexAsc(String chapterId);

    List<ChapterNarrationSegmentJpaEntity> findByChapterIdAndStatusOrderBySegmentIndexAsc(String chapterId, String status);
}
