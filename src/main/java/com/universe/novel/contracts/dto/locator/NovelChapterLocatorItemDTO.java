package com.universe.novel.contracts.dto.locator;

import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight read model for a located published chapter in navigation search.
 */
public record NovelChapterLocatorItemDTO(
        UUID id,
        int chapterNumber,
        String title,
        String slug,
        int volumeSortOrder,
        String volumeTitle
) {
    public NovelChapterLocatorItemDTO {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
    }
}
