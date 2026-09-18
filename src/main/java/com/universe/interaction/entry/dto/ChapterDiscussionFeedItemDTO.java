package com.universe.interaction.entry.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable public read representation of a chapter discussion feed item.
 *
 * <p>Preserves clean architecture contracts:
 * <ul>
 *   <li>Interaction entry DTO: zero dependency on JPA entities or internal Novel models;</li>
 *   <li>Exposes safe read-first discussion and secondary passage reference fields;</li>
 *   <li>Contains server-authoritative mutation capabilities (canEdit, canDelete) for the viewer;</li>
 *   <li>Contains immutable, chronologically ordered visible replies nested under their root;</li>
 *   <li>Edited flag derived from {@code updatedAt > createdAt};</li>
 *   <li>Secondary blockKey is null for STALE and UNANCHORED anchors to prevent invalid navigation.</li>
 * </ul>
 */
public record ChapterDiscussionFeedItemDTO(
        UUID rootCommentId,
        CommentAuthorDTO author,
        String body,
        Instant createdAt,
        Instant updatedAt,
        boolean edited,
        boolean canEdit,
        boolean canDelete,
        int replyCount,
        String anchorStatus,
        String blockKey,
        String passageExcerpt,
        List<CommentReadDTO> replies
) {
    public ChapterDiscussionFeedItemDTO {
        Objects.requireNonNull(rootCommentId, "rootCommentId cannot be null");
        Objects.requireNonNull(author, "author cannot be null");
        Objects.requireNonNull(body, "body cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        Objects.requireNonNull(anchorStatus, "anchorStatus cannot be null");
        replies = replies == null ? List.of() : List.copyOf(replies);
    }
}
