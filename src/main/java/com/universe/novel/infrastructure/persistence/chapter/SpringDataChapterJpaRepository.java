package com.universe.novel.infrastructure.persistence.chapter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataChapterJpaRepository
        extends JpaRepository<ChapterJpaEntity, String> {

    Optional<ChapterJpaEntity> findBySlug(
            String slug
    );

    boolean existsBySlug(
            String slug
    );

    boolean existsByVolumeIdAndSortOrder(
            String volumeId,
            int sortOrder
    );

    boolean existsByVolumeIdAndStatus(
            String volumeId,
            String status
    );
}