package com.universe.novel.contracts.dto.profile;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;

import java.time.Instant;
import java.util.UUID;

public record NovelProfileDTO(
        UUID id,
        String title,
        String slug,
        String author,
        String description,
        String coverImageUrl,
        UUID coverMediaAssetId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    public NovelProfileDTO(
            UUID id,
            String title,
            String slug,
            String author,
            String description,
            String coverImageUrl,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, title, slug, author, description, coverImageUrl, null, status, createdAt, updatedAt);
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
