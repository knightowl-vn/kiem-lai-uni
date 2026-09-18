package com.universe.interaction.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain representation of an archived comment revision snapshot.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Immutable pure Java domain record with zero ORM/framework dependencies;</li>
 *   <li>Created strictly when an ACTIVE comment is edited to archive its prior body;</li>
 *   <li>{@code revisionNumber} is 1-based and monotonically increasing per comment;</li>
 *   <li>Stored body is normalized (trimmed, non-empty), identical to {@link Comment} body rules;</li>
 *   <li>Author identity is derived from the parent {@link Comment}, avoiding denormalization anomalies;</li>
 *   <li>Read-only historical transparency: contains no restore/revert capabilities.</li>
 * </ul>
 */
public record CommentRevision(
        UUID id,
        UUID commentId,
        int revisionNumber,
        String body,
        Instant createdAt
) {

    public CommentRevision {
        Objects.requireNonNull(id, "Revision ID cannot be null.");
        Objects.requireNonNull(commentId, "Comment ID cannot be null.");

        if (revisionNumber < 1) {
            throw new IllegalArgumentException("Revision number must be greater than or equal to 1: " + revisionNumber);
        }

        if (body == null) {
            throw new IllegalArgumentException("Revision body cannot be null.");
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Revision body cannot be blank.");
        }
        body = trimmed;

        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
    }

    public UUID getId() {
        return id();
    }

    public UUID getCommentId() {
        return commentId();
    }

    public int getRevisionNumber() {
        return revisionNumber();
    }

    public String getBody() {
        return body();
    }

    public Instant getCreatedAt() {
        return createdAt();
    }
}
