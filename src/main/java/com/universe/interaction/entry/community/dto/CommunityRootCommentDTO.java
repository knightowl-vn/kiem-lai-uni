package com.universe.interaction.entry.community.dto;

import com.universe.interaction.entry.dto.CommentReadDTO;

import java.util.Objects;

/**
 * Immutable entry read model representing an active root comment in the Community discussion feed.
 *
 * <p>Contains the root comment read model and its active reply count without materializing
 * reply bodies or hydrating child entities.
 */
public record CommunityRootCommentDTO(
        CommentReadDTO root,
        long replyCount
) {
    public CommunityRootCommentDTO {
        Objects.requireNonNull(root, "Root comment cannot be null.");
        if (replyCount < 0) {
            throw new IllegalArgumentException("replyCount cannot be negative: " + replyCount);
        }
    }
}
