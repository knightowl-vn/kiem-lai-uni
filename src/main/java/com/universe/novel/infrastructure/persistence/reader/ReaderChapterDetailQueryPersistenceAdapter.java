package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderChapterDetailQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.infrastructure.persistence.chapter.ReaderChapterDetailProjection;
import com.universe.novel.infrastructure.persistence.chapter.ReaderChapterListItemProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReaderChapterDetailQueryPersistenceAdapter
        implements ReaderChapterDetailQueryPort {

    private final SpringDataChapterJpaRepository
            chapterRepository;

    public ReaderChapterDetailQueryPersistenceAdapter(
            SpringDataChapterJpaRepository chapterRepository
    ) {
        this.chapterRepository =
                chapterRepository;
    }

    @Override
    public Optional<ReaderChapterRecord> findPublishedChapterBySlug(
            String chapterSlug
    ) {
        return chapterRepository
                .findPublishedReaderChapterBySlug(
                        chapterSlug
                )
                .map(
                        this::toChapterRecord
                );
    }

    @Override
    public Optional<ReaderChapterNavigationDTO> findPreviousPublishedChapter(
            int currentChapterNumber
    ) {
        return chapterRepository
                .findPreviousPublishedReaderChapter(
                        currentChapterNumber
                )
                .map(
                        this::toNavigationDTO
                );
    }

    @Override
    public Optional<ReaderChapterNavigationDTO> findNextPublishedChapter(
            int currentChapterNumber
    ) {
        return chapterRepository
                .findNextPublishedReaderChapter(
                        currentChapterNumber
                )
                .map(
                        this::toNavigationDTO
                );
    }

    @Override
    public List<ReaderChapterTocItemDTO> findAllPublishedChaptersForToc() {
        return chapterRepository
                .findAllPublishedReaderChaptersOrderByChapterNumber()
                .stream()
                .map(
                        this::toTocItemDTO
                )
                .toList();
    }

    private ReaderChapterRecord toChapterRecord(
            ReaderChapterDetailProjection projection
    ) {
        return new ReaderChapterRecord(
                UUID.fromString(
                        projection.getId()
                ),
                UUID.fromString(
                        projection.getVolumeId()
                ),
                projection.getChapterNumber(),
                projection.getTitle(),
                projection.getSlug(),
                projection.getContent(),
                projection.getVolumeTitle(),
                projection.getVolumeSlug(),
                projection.getVolumeSortOrder()
        );
    }

    private ReaderChapterNavigationDTO toNavigationDTO(
            ReaderChapterListItemProjection projection
    ) {
        return new ReaderChapterNavigationDTO(
                projection.getChapterNumber(),
                projection.getTitle(),
                projection.getSlug()
        );
    }

    private ReaderChapterTocItemDTO toTocItemDTO(
            ReaderChapterListItemProjection projection
    ) {
        return new ReaderChapterTocItemDTO(
                projection.getChapterNumber(),
                projection.getTitle(),
                projection.getSlug()
        );
    }
}
