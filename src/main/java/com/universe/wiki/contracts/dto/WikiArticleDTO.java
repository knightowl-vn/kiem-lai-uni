package com.universe.wiki.contracts.dto;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu công khai của một Wiki Article.
 *
 * DTO không để lộ enum hoặc Aggregate nội bộ.
 */
public record WikiArticleDTO(
        UUID id,
        String title,
        String slug,
        String articleType,
        String summary,
        String content,
        String status,
        UUID createdBy,
        UUID updatedBy,
        UUID publishedBy,
        UUID archivedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant archivedAt,
        long aggregateVersion,
        long contentVersion,
        UUID coverMediaAssetId,
        int coverPositionX,
        int coverPositionY
) {

    public WikiArticleDTO(
            UUID id,
            String title,
            String slug,
            String articleType,
            String summary,
            String content,
            String status,
            UUID createdBy,
            UUID updatedBy,
            UUID publishedBy,
            UUID archivedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant archivedAt,
            long aggregateVersion,
            long contentVersion,
            UUID coverMediaAssetId
    ) {
        this(
                id,
                title,
                slug,
                articleType,
                summary,
                content,
                status,
                createdBy,
                updatedBy,
                publishedBy,
                archivedBy,
                createdAt,
                updatedAt,
                publishedAt,
                archivedAt,
                aggregateVersion,
                contentVersion,
                coverMediaAssetId,
                50,
                50
        );
    }

    public WikiArticleDTO(
            UUID id,
            String title,
            String slug,
            String articleType,
            String summary,
            String content,
            String status,
            UUID createdBy,
            UUID updatedBy,
            UUID publishedBy,
            UUID archivedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant archivedAt,
            long aggregateVersion,
            long contentVersion
    ) {
        this(
                id,
                title,
                slug,
                articleType,
                summary,
                content,
                status,
                createdBy,
                updatedBy,
                publishedBy,
                archivedBy,
                createdAt,
                updatedAt,
                publishedAt,
                archivedAt,
                aggregateVersion,
                contentVersion,
                null,
                50,
                50
        );
    }

    public String displayCoverImageUrl() {
        return coverMediaAssetId != null
                ? MediaDeliveryUrlSupport.variantUrl(coverMediaAssetId, 300)
                : null;
    }

    public String fallbackCoverImageUrl() {
        return coverMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(coverMediaAssetId)
                : null;
    }

    public String coverObjectPosition() {
        return coverPositionX + "% " + coverPositionY + "%";
    }
}