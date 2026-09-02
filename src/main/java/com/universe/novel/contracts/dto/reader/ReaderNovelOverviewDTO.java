package com.universe.novel.contracts.dto.reader;

import java.util.UUID;

public record ReaderNovelOverviewDTO(
        String title,
        String slug,
        String author,
        String description,
        String coverImageUrl,
        UUID coverMediaAssetId,
        String status
) {

    public ReaderNovelOverviewDTO(
            String title,
            String slug,
            String author,
            String description,
            String coverImageUrl,
            String status
    ) {
        this(title, slug, author, description, coverImageUrl, null, status);
    }
}