package com.universe.novel.infrastructure.persistence.chapter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface SpringDataChapterJpaRepository
        extends JpaRepository<ChapterJpaEntity, String> {

    Optional<ChapterJpaEntity> findBySlug(
            String slug
    );

    boolean existsBySlug(
            String slug
    );
    
    List<ChapterJpaEntity> findAllByVolumeIdOrderBySortOrderAsc(
            String volumeId
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