package com.universe.interaction.entry.dto;

import com.universe.interaction.domain.CommentRevision;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only public DTO exposing archived comment revision history.
 *
 * <p>Publicly exposes strictly:
 * <ul>
 *   <li>{@code revisionNumber}: 1-based monotonic sequence number;</li>
 *   <li>{@code body}: normalized historical comment body;</li>
 *   <li>{@code createdAt}: snapshot timestamp.</li>
 * </ul>
 *
 * <p>Never exposes internal revision UUIDs, comment IDs, author identities, or moderation metadata.
 */
public record CommentRevisionReadDTO(
        int revisionNumber,
        String body,
        Instant createdAt
) {

    public CommentRevisionReadDTO {
        Objects.requireNonNull(body, "Revision body cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
    }

    public static CommentRevisionReadDTO from(CommentRevision revision) {
        Objects.requireNonNull(revision, "CommentRevision cannot be null.");
        return new CommentRevisionReadDTO(
                revision.getRevisionNumber(),
                revision.getBody(),
                revision.getCreatedAt()
        );
    }
}
