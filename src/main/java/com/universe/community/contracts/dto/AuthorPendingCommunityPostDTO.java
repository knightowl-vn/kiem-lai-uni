package com.universe.community.contracts.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Projection DTO representing an authenticated author's pending post awaiting moderation review.
 */
public record AuthorPendingCommunityPostDTO(
        UUID id,
        UUID authorUserId,
        String authorDisplayName,
        String authorPublicHandle,
        String authorAvatarUrl,
        String caption,
        String pendingCaption,
        UUID imageMediaAssetId,
        String imageUrl,
        Instant createdAt,
        Instant reviewRequestedAt,
        Instant publishedAt,
        String status,
        int contentVersion
) {
    public AuthorPendingCommunityPostDTO {
        Objects.requireNonNull(id, "Post ID cannot be null.");
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(caption, "Caption cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt cannot be null.");
        Objects.requireNonNull(status, "Status cannot be null.");
    }

    public AuthorPendingCommunityPostDTO(
            UUID id,
            UUID authorUserId,
            String authorDisplayName,
            String authorPublicHandle,
            String authorAvatarUrl,
            String caption,
            UUID imageMediaAssetId,
            String imageUrl,
            Instant createdAt,
            Instant reviewRequestedAt,
            Instant publishedAt,
            String status,
            int contentVersion
    ) {
        this(id, authorUserId, authorDisplayName, authorPublicHandle, authorAvatarUrl, caption, null, imageMediaAssetId, imageUrl, createdAt, reviewRequestedAt, publishedAt, status, contentVersion);
    }

    public boolean isPendingCaptionEdit() {
        return pendingCaption != null;
    }

    public boolean isReReview() {
        return isPendingCaptionEdit() || publishedAt != null;
    }

    public String getStatusBadgeText() {
        return isPendingCaptionEdit() ? "Đang chờ duyệt chỉnh sửa" : "Đang chờ duyệt";
    }

    public String displayCaption() {
        return pendingCaption != null ? pendingCaption : caption;
    }

    public String getDisplayCaption() {
        return displayCaption();
    }
}
