package com.universe.community.domain;

import com.universe.community.domain.exception.CommunityPostValidationException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain representation of a historical Community Post edit snapshot.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Immutable pure Java record with zero ORM/framework dependencies;</li>
 *   <li>{@code revisionNumber} is 1-based and monotonically increasing per post;</li>
 *   <li>Archived {@code previousCaption} and updated {@code caption} are normalized (trimmed, non-blank, &le; 2,000 characters);</li>
 *   <li>{@code editorUserId} records the actor who made the edit;</li>
 *   <li>{@code editedAt} records the exact moment the revision was created;</li>
 *   <li>Read-only historical transparency without restore/revert capabilities.</li>
 * </ul>
 */
public record CommunityPostRevision(
        UUID id,
        UUID postId,
        int revisionNumber,
        UUID editorUserId,
        String previousCaption,
        String caption,
        Instant editedAt
) {

    public static final int MAX_CAPTION_LENGTH = 2000;

    public CommunityPostRevision {
        Objects.requireNonNull(id, "Revision ID cannot be null.");
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Objects.requireNonNull(editorUserId, "Editor user ID cannot be null.");
        Objects.requireNonNull(editedAt, "EditedAt timestamp cannot be null.");

        if (revisionNumber < 1) {
            throw new CommunityPostValidationException("Revision number must be greater than or equal to 1: " + revisionNumber);
        }

        previousCaption = validateCaption(previousCaption, "Previous caption");
        caption = validateCaption(caption, "Caption");
    }

    private static String validateCaption(String rawCaption, String fieldName) {
        if (rawCaption == null) {
            throw new CommunityPostValidationException(fieldName + " cannot be null.");
        }
        String trimmed = rawCaption.trim();
        if (trimmed.isEmpty()) {
            throw new CommunityPostValidationException(fieldName + " cannot be blank.");
        }
        if (trimmed.length() > MAX_CAPTION_LENGTH) {
            throw new CommunityPostValidationException(
                    fieldName + " length (" + trimmed.length() + ") exceeds maximum limit of " + MAX_CAPTION_LENGTH + " characters."
            );
        }
        return trimmed;
    }

    public UUID getId() {
        return id();
    }

    public UUID getPostId() {
        return postId();
    }

    public int getRevisionNumber() {
        return revisionNumber();
    }

    public UUID getEditorUserId() {
        return editorUserId();
    }

    public String getPreviousCaption() {
        return previousCaption();
    }

    public String getCaption() {
        return caption();
    }

    public Instant getEditedAt() {
        return editedAt();
    }
}
