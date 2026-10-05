package com.universe.community.contracts.dto;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public read projection DTO representing a Community Post.
 */
public record CommunityPostPublicDTO(
        UUID id,
        UUID authorUserId,
        String caption,
        UUID imageMediaAssetId,
        String imageUrl,
        int contentVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        String status
) {

    public CommunityPostPublicDTO {
        Objects.requireNonNull(id, "Post ID cannot be null.");
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(caption, "Caption cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");
    }

    /**
     * Canonical constructor without explicit imageUrl that derives imageUrl automatically.
     */
    public CommunityPostPublicDTO(
            UUID id,
            UUID authorUserId,
            String caption,
            UUID imageMediaAssetId,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            String status
    ) {
        this(
                id,
                authorUserId,
                caption,
                imageMediaAssetId,
                imageMediaAssetId != null ? MediaDeliveryUrlSupport.contentUrl(imageMediaAssetId) : null,
                contentVersion,
                createdAt,
                updatedAt,
                publishedAt,
                status
        );
    }

    /**
     * Backward-compatible 8-parameter constructor defaulting publishedAt to createdAt and status to PUBLISHED.
     */
    public CommunityPostPublicDTO(
            UUID id,
            UUID authorUserId,
            String caption,
            UUID imageMediaAssetId,
            String imageUrl,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                authorUserId,
                caption,
                imageMediaAssetId,
                imageUrl,
                contentVersion,
                createdAt,
                updatedAt,
                createdAt,
                "PUBLISHED"
        );
    }

    /**
     * Backward-compatible 7-parameter constructor defaulting publishedAt to createdAt and status to PUBLISHED.
     */
    public CommunityPostPublicDTO(
            UUID id,
            UUID authorUserId,
            String caption,
            UUID imageMediaAssetId,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                authorUserId,
                caption,
                imageMediaAssetId,
                contentVersion,
                createdAt,
                updatedAt,
                createdAt,
                "PUBLISHED"
        );
    }
}
