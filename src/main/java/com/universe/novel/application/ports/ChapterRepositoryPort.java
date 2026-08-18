package com.universe.novel.application.ports;

import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Slug;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterRepositoryPort {

    Optional<Chapter> findById(
            UUID id
    );

    Optional<Chapter> findBySlug(
            Slug slug
    );
    
    List<Chapter> findAllByVolumeIdOrderBySortOrder(
            UUID volumeId
    );

    boolean existsBySlug(
            Slug slug
    );

    boolean existsByVolumeIdAndSortOrder(
            UUID volumeId,
            int sortOrder
    );

    boolean existsPublishedByVolumeId(
            UUID volumeId
    );

    Chapter save(
            Chapter chapter,
            long expectedAggregateVersion
    );
}
