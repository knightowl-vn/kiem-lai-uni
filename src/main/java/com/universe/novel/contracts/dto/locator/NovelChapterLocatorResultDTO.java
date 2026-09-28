package com.universe.novel.contracts.dto.locator;

import java.util.List;
import java.util.Objects;

/**
 * Result DTO returned by the Novel Chapter Locator.
 */
public record NovelChapterLocatorResultDTO(
        String query,
        Integer matchedChapterNumber,
        List<NovelChapterLocatorItemDTO> items
) {
    public NovelChapterLocatorResultDTO {
        Objects.requireNonNull(query, "query must not be null");
        items = items != null ? List.copyOf(items) : List.of();
    }
}
