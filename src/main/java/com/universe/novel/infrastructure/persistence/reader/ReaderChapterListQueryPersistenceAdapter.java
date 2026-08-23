package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderChapterListQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;
import com.universe.novel.infrastructure.persistence.chapter.ReaderChapterListItemProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ReaderChapterListQueryPersistenceAdapter
        implements ReaderChapterListQueryPort {

    private final SpringDataChapterJpaRepository
            chapterRepository;

    public ReaderChapterListQueryPersistenceAdapter(
            SpringDataChapterJpaRepository chapterRepository
    ) {
        this.chapterRepository =
                chapterRepository;
    }

    @Override
    public List<ReaderChapterListItemDTO>
            findPublishedByVolumeIdOrderByChapterNumber(
                    UUID volumeId
            ) {

        return chapterRepository
                .findPublishedReaderChaptersByVolumeId(
                        volumeId.toString()
                )
                .stream()
                .map(
                        this::toDTO
                )
                .toList();
    }

    private ReaderChapterListItemDTO toDTO(
            ReaderChapterListItemProjection projection
    ) {
        return new ReaderChapterListItemDTO(
                UUID.fromString(
                        projection.getId()
                ),
                projection.getChapterNumber(),
                projection.getTitle(),
                projection.getSlug()
        );
    }
}