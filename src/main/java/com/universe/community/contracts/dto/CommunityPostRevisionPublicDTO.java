package com.universe.community.contracts.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public read projection DTO representing an immutable Community Post Revision.
 */
public record CommunityPostRevisionPublicDTO(
        UUID id,
        UUID postId,
        int revisionNumber,
        UUID editorUserId,
        String previousCaption,
        String caption,
        Instant editedAt
) {

    public CommunityPostRevisionPublicDTO {
        Objects.requireNonNull(id, "Revision ID cannot be null.");
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Objects.requireNonNull(editorUserId, "Editor user ID cannot be null.");
        Objects.requireNonNull(previousCaption, "Previous caption cannot be null.");
        Objects.requireNonNull(caption, "Caption cannot be null.");
        Objects.requireNonNull(editedAt, "EditedAt timestamp cannot be null.");
    }
}
