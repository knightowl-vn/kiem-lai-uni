package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.novel.contracts.dto.ChapterListPageDTO;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ChapterListQueryPort {

    ChapterListPageDTO findAllByVolumeIdOrderByChapterNumber(
            UUID volumeId,
            String keyword,
            String status,
            int page,
            int size
    );

    /**
     * Batch lookup of lightweight chapter metadata by IDs.
     *
     * @param chapterIds set of chapter IDs to find
     * @return map of chapter ID to ChapterListItemDTO for all found chapters
     */
    Map<UUID, ChapterListItemDTO> findListItemsByIds(Set<UUID> chapterIds);
}

