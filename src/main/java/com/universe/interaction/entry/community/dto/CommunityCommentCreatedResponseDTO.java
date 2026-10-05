package com.universe.interaction.entry.community.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Response payload returned upon successful creation of a root comment or reply on a Community post.
 */
public record CommunityCommentCreatedResponseDTO(
        UUID commentId,
        long updatedCommentCount
) {
    public CommunityCommentCreatedResponseDTO {
        Objects.requireNonNull(commentId, "commentId cannot be null");
        if (updatedCommentCount < 0) {
            throw new IllegalArgumentException("updatedCommentCount cannot be negative: " + updatedCommentCount);
        }
    }
}
