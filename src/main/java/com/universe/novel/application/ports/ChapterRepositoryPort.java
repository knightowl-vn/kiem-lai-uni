package com.universe.novel.application.ports;

import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Slug;

import java.util.Optional;
import java.util.UUID;

public interface ChapterRepositoryPort {

    Optional<Chapter> findById(
            UUID id
    );

    Optional<Chapter> findBySlug(
            Slug slug
    );

    boolean existsBySlug(
            Slug slug
    );

    boolean existsByChapterNumber(
            int chapterNumber
    );

    boolean existsPublishedByVolumeId(
            UUID volumeId
    );

    Chapter save(
            Chapter chapter,
            long expectedAggregateVersion
    );

    void delete(
            Chapter chapter,
            long expectedAggregateVersion
    );
}
