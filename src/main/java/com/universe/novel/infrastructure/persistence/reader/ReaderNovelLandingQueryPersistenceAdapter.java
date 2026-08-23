package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderNovelLandingQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;
import com.universe.novel.infrastructure.persistence.profile.NovelProfileJpaEntity;
import com.universe.novel.infrastructure.persistence.profile.SpringDataNovelProfileJpaRepository;
import com.universe.novel.infrastructure.persistence.volume.ReaderVolumeListItemProjection;
import com.universe.novel.infrastructure.persistence.volume.SpringDataVolumeJpaRepository;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReaderNovelLandingQueryPersistenceAdapter
        implements ReaderNovelLandingQueryPort {

    private static final String NOVEL_SLUG =
            "kiem-lai";

    private final SpringDataNovelProfileJpaRepository
            novelProfileRepository;

    private final SpringDataVolumeJpaRepository
            volumeRepository;

    public ReaderNovelLandingQueryPersistenceAdapter(
            SpringDataNovelProfileJpaRepository novelProfileRepository,
            SpringDataVolumeJpaRepository volumeRepository
    ) {
        this.novelProfileRepository =
                novelProfileRepository;

        this.volumeRepository =
                volumeRepository;
    }

    @Override
    public Optional<ReaderNovelOverviewDTO> findNovelOverview() {
        return novelProfileRepository
                .findBySlug(
                        NOVEL_SLUG
                )
                .map(
                        this::toNovelOverviewDTO
                );
    }

    @Override
    public List<ReaderVolumeListItemDTO> findPublishedVolumes() {
        return volumeRepository
                .findPublishedReaderVolumes()
                .stream()
                .map(
                        this::toVolumeListItemDTO
                )
                .toList();
    }

    private ReaderNovelOverviewDTO toNovelOverviewDTO(
            NovelProfileJpaEntity entity
    ) {
        return new ReaderNovelOverviewDTO(
                entity.getTitle(),
                entity.getSlug(),
                entity.getAuthor(),
                entity.getDescription(),
                entity.getCoverImageUrl(),
                entity.getStatus()
        );
    }

    private ReaderVolumeListItemDTO toVolumeListItemDTO(
            ReaderVolumeListItemProjection projection
    ) {
        return new ReaderVolumeListItemDTO(
                UUID.fromString(
                        projection.getId()
                ),
                projection.getTitle(),
                projection.getSlug(),
                projection.getSortOrder(),
                projection.getPublishedChapterCount()
        );
    }
}