package com.universe.novel.infrastructure.persistence.narration;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataChapterNarrationSegmentJpaRepository
        extends JpaRepository<ChapterNarrationSegmentJpaEntity, String> {

    List<ChapterNarrationSegmentJpaEntity> findByChapterIdOrderBySegmentIndexAsc(String chapterId);

    List<ChapterNarrationSegmentJpaEntity> findByChapterIdAndStatusOrderBySegmentIndexAsc(String chapterId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ChapterNarrationSegmentJpaEntity s WHERE s.id = :id")
    Optional<ChapterNarrationSegmentJpaEntity> findByIdForUpdate(@Param("id") String id);
}
