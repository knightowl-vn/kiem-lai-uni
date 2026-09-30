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
        Instant updatedAt
) {

    public CommunityPostPublicDTO {
        Objects.requireNonNull(id, "Post ID cannot be null.");
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(caption, "Caption cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");
    }

    /**
     * Backward-compatible 7-parameter constructor that derives {@code imageUrl} automatically
     * from {@code imageMediaAssetId} via {@link MediaDeliveryUrlSupport#contentUrl(UUID)}.
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
                imageMediaAssetId != null ? MediaDeliveryUrlSupport.contentUrl(imageMediaAssetId) : null,
                contentVersion,
                createdAt,
                updatedAt
        );
    }
}
