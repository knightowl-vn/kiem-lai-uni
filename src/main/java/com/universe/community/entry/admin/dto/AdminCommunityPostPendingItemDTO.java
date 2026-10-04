package com.universe.community.entry.admin.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Item representation for Admin Pending Community Post review queue.
 */
public record AdminCommunityPostPendingItemDTO(
        UUID postId,
        AdminCommunityPostUserDTO author,
        String caption,
        String pendingCaption,
        UUID imageMediaAssetId,
        Instant createdAt,
        Instant reviewRequestedAt,
        Instant publishedAt,
        int contentVersion
) {
    public AdminCommunityPostPendingItemDTO {
        Objects.requireNonNull(postId, "postId cannot be null.");
        Objects.requireNonNull(author, "author cannot be null.");
        Objects.requireNonNull(caption, "caption cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
    }

    public AdminCommunityPostPendingItemDTO(
            UUID postId,
            AdminCommunityPostUserDTO author,
            String caption,
            UUID imageMediaAssetId,
            Instant createdAt,
            Instant reviewRequestedAt,
            Instant publishedAt,
            int contentVersion
    ) {
        this(postId, author, caption, null, imageMediaAssetId, createdAt, reviewRequestedAt, publishedAt, contentVersion);
    }

    public AdminCommunityPostPendingItemDTO(
            UUID postId,
            AdminCommunityPostUserDTO author,
            String caption,
            UUID imageMediaAssetId,
            Instant createdAt,
            Instant reviewRequestedAt,
            int contentVersion
    ) {
        this(postId, author, caption, null, imageMediaAssetId, createdAt, reviewRequestedAt, null, contentVersion);
    }

    public boolean isPendingCaptionEdit() {
        return pendingCaption != null;
    }

    public String getStatusBadgeText() {
        return isPendingCaptionEdit() ? "Chờ duyệt chỉnh sửa" : "Chờ duyệt";
    }
}
