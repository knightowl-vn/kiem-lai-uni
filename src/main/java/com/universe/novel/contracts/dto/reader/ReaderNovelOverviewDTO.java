package com.universe.novel.contracts.dto.reader;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;

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

    public String displayCoverImageUrl() {
        if (coverMediaAssetId != null) {
            return MediaDeliveryUrlSupport.variantUrl(coverMediaAssetId, 300);
        }
        return coverImageUrl;
    }

    public String fallbackCoverImageUrl() {
        if (coverMediaAssetId != null) {
            return MediaDeliveryUrlSupport.contentUrl(coverMediaAssetId);
        }
        return coverImageUrl;
    }
}