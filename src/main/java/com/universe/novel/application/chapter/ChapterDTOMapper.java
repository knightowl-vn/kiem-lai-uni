package com.universe.novel.application.chapter;

import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;

import java.util.Objects;

final class ChapterDTOMapper {

    private ChapterDTOMapper() {
    }

    static ChapterDTO toDTO(
            Chapter chapter
    ) {
        Objects.requireNonNull(
                chapter,
                "Chapter không được để trống."
        );

        return new ChapterDTO(
                chapter.getId(),
                chapter.getVolumeId(),
                chapter.getChapterNumber(),
                chapter.getSortOrder(),
                chapter.getTitle(),
                chapter.getSlug().value(),
                chapter.getSummary(),
                chapter.getContent(),
                chapter.getStatus().name(),
                chapter.getCreatedBy(),
                chapter.getUpdatedBy(),
                chapter.getPublishedBy(),
                chapter.getArchivedBy(),
                chapter.getCreatedAt(),
                chapter.getUpdatedAt(),
                chapter.getPublishedAt(),
                chapter.getArchivedAt(),
                chapter.getAggregateVersion(),
                chapter.getContentVersion()
        );
    }
}