package com.universe.community.domain;

import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.community.domain.exception.CommunityPostValidationException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing a Community Post.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Immutable scalar UUID identity ({@code id});</li>
 *   <li>Immutable scalar author user ID ({@code authorUserId});</li>
 *   <li>Caption is trimmed, non-blank, and maximum 2,000 characters;</li>
 *   <li>Optional image media asset ID ({@code imageMediaAssetId}) is strictly immutable after creation;</li>
 *   <li>{@code contentVersion} begins at 0 and increments monotonically upon each effective caption edit;</li>
 *   <li>{@code createdAt} is immutable; {@code updatedAt} tracks the latest state transition timestamp;</li>
 *   <li>Editing caption with identical normalized text is an idempotent no-op;</li>
 *   <li>Zero framework or ORM dependencies (pure Java domain).</li>
 * </ul>
 */
public final class CommunityPost {

    public static final int MAX_CAPTION_LENGTH = 2000;

    private final UUID id;
    private final UUID authorUserId;
    private String caption;
    private final UUID imageMediaAssetId;
    private int contentVersion;
    private final Instant createdAt;
    private Instant updatedAt;

    private CommunityPost(
            UUID id,
            UUID authorUserId,
            String caption,
            UUID imageMediaAssetId,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "Post ID cannot be null.");
        this.authorUserId = Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        this.caption = validateCaption(caption);
        this.imageMediaAssetId = imageMediaAssetId;

        if (contentVersion < 0) {
            throw new CommunityPostValidationException("Content version cannot be negative: " + contentVersion);
        }
        this.contentVersion = contentVersion;

        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");

        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new CommunityPostValidationException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
    }

    /**
     * Factory method to create a new CommunityPost aggregate root.
     */
    public static CommunityPost create(
            UUID id,
            UUID authorUserId,
            String caption,
            UUID imageMediaAssetId,
            Instant createdAt
    ) {
        Objects.requireNonNull(id, "Post ID cannot be null.");
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        String validatedCaption = validateCaption(caption);

        return new CommunityPost(
                id,
                authorUserId,
                validatedCaption,
                imageMediaAssetId,
                0,
                createdAt,
                createdAt
        );
    }

    /**
     * Rehydrates a CommunityPost from persistence storage.
     */
    public static CommunityPost rehydrate(
            UUID id,
            UUID authorUserId,
            String caption,
            UUID imageMediaAssetId,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new CommunityPost(
                id,
                authorUserId,
                caption,
                imageMediaAssetId,
                contentVersion,
                createdAt,
                updatedAt
        );
    }

    /**
     * Edits the caption of this post.
     *
     * @param actorUserId the user attempting the edit
     * @param newCaption the updated caption content
     * @param editedAt the timestamp of the edit
     * @return {@code true} if the caption was changed, {@code false} if it was an idempotent no-op
     */
    public boolean editCaption(
            UUID actorUserId,
            String newCaption,
            Instant editedAt
    ) {
        if (actorUserId == null || !this.authorUserId.equals(actorUserId)) {
            throw new CommunityPostUnauthorizedException(actorUserId, this.id);
        }

        String validatedNewCaption = validateCaption(newCaption);

        Objects.requireNonNull(editedAt, "Edit timestamp cannot be null.");
        if (editedAt.isBefore(this.updatedAt)) {
            throw new CommunityPostValidationException("Edit timestamp cannot be before the last updated timestamp.");
        }

        if (validatedNewCaption.equals(this.caption)) {
            return false;
        }

        this.caption = validatedNewCaption;
        this.contentVersion++;
        this.updatedAt = editedAt;
        return true;
    }

    private static String validateCaption(String rawCaption) {
        if (rawCaption == null) {
            throw new CommunityPostValidationException("Post caption cannot be null.");
        }
        String trimmed = rawCaption.trim();
        if (trimmed.isEmpty()) {
            throw new CommunityPostValidationException("Post caption cannot be blank.");
        }
        if (trimmed.length() > MAX_CAPTION_LENGTH) {
            throw new CommunityPostValidationException(
                    "Post caption length (" + trimmed.length() + ") exceeds maximum limit of " + MAX_CAPTION_LENGTH + " characters."
            );
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public String getCaption() {
        return caption;
    }

    public UUID getImageMediaAssetId() {
        return imageMediaAssetId;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommunityPost that = (CommunityPost) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CommunityPost{" +
                "id=" + id +
                ", authorUserId=" + authorUserId +
                ", caption='" + (caption != null && caption.length() > 30 ? caption.substring(0, 30) + "..." : caption) + '\'' +
                ", imageMediaAssetId=" + imageMediaAssetId +
                ", contentVersion=" + contentVersion +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
