package com.universe.novel.infrastructure.persistence.revision;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataChapterRevisionJpaRepository
        extends JpaRepository<ChapterRevisionJpaEntity, String> {

    Optional<ChapterRevisionJpaEntity> findByChapterIdAndRevisionNumber(
            String chapterId,
            long revisionNumber
    );

    Page<ChapterRevisionJpaEntity> findByChapterId(
            String chapterId,
            Pageable pageable
    );

    List<ChapterRevisionJpaEntity> findByChapterIdOrderByRevisionNumberAsc(
            String chapterId
    );

    @Modifying
    @Query("delete from ChapterRevisionJpaEntity r where r.chapterId = :chapterId")
    void deleteByChapterId(@Param("chapterId") String chapterId);

    long countByChapterId(String chapterId);
}
