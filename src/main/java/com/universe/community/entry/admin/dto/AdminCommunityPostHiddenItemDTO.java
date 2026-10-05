package com.universe.community.entry.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Item representation for Admin Hidden Community Posts management.
 */
public record AdminCommunityPostHiddenItemDTO(
        UUID postId,
        AdminCommunityPostUserDTO author,
        String caption,
        UUID imageMediaAssetId,
        Instant createdAt,
        Instant updatedAt,
        int contentVersion,
        List<AdminCommunityPostModerationEventDTO> moderationHistory
) {
    public AdminCommunityPostHiddenItemDTO {
        Objects.requireNonNull(postId, "postId cannot be null.");
        Objects.requireNonNull(author, "author cannot be null.");
        Objects.requireNonNull(caption, "caption cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
        moderationHistory = moderationHistory != null ? List.copyOf(moderationHistory) : List.of();
    }
}
