package com.universe.community.domain;

import com.universe.community.domain.exception.CommunityPostPendingEditConflictException;
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
 *   <li>Canonical moderation lifecycle status ({@code status}): PUBLISHED, PENDING_REVIEW, HIDDEN, REJECTED;</li>
 *   <li>{@code publishedAt}: non-null for PUBLISHED and HIDDEN posts; tracks initial public appearance;</li>
 *   <li>{@code reviewRequestedAt}: non-null for posts in review (either PENDING_REVIEW or PUBLISHED with pendingCaption); null otherwise;</li>
 *   <li>Editing caption is strictly allowed only when status is PUBLISHED;</li>
 *   <li>Editing caption under PRE_MODERATION retains status PUBLISHED and public caption, storing candidate caption in pendingCaption;</li>
 *   <li>Single candidate barrier: subsequent edits while pendingCaption is present throw {@link CommunityPostPendingEditConflictException};</li>
 *   <li>Editing caption with identical normalized text is an idempotent no-op;</li>
 *   <li>{@code contentVersion} begins at 0 and increments monotonically upon each approved/effective caption edit;</li>
 *   <li>{@code createdAt} is immutable; {@code updatedAt} tracks the latest state transition timestamp;</li>
 *   <li>Zero framework or ORM dependencies (pure Java domain).</li>
 * </ul>
 */
public final class CommunityPost {

    public static final int MAX_CAPTION_LENGTH = 2000;

    private final UUID id;
    private final UUID authorUserId;
    private String caption;
    private String pendingCaption;
    private final UUID imageMediaAssetId;
    private CommunityPostStatus status;
    private int contentVersion;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
    private Instant reviewRequestedAt;

    private CommunityPost(
            UUID id,
            UUID authorUserId,
            String caption,
            String pendingCaption,
            UUID imageMediaAssetId,
            CommunityPostStatus status,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant reviewRequestedAt
    ) {
        this.id = Objects.requireNonNull(id, "Post ID cannot be null.");
        this.authorUserId = Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        this.caption = validateCaption(caption);
        this.pendingCaption = pendingCaption != null ? validateCaption(pendingCaption) : null;
        this.imageMediaAssetId = imageMediaAssetId;
        this.status = Objects.requireNonNull(status, "Status cannot be null.");

        if (contentVersion < 0) {
            throw new CommunityPostValidationException("Content version cannot be negative: " + contentVersion);
        }
        this.contentVersion = contentVersion;

        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");

        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new CommunityPostValidationException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }

        validateStatusInvariants(status, publishedAt, reviewRequestedAt, this.pendingCaption);
        this.publishedAt = publishedAt;
        this.reviewRequestedAt = reviewRequestedAt;
    }

    /**
     * Factory method to create a new CommunityPost aggregate root.
     */
    public static CommunityPost create(
            UUID id,
            UUID authorUserId,
            String caption,
            UUID imageMediaAssetId,
            CommunityPostStatus status,
            Instant createdAt,
            Instant publishedAt,
            Instant reviewRequestedAt
    ) {
        Objects.requireNonNull(id, "Post ID cannot be null.");
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(status, "Status cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        validateStatusInvariants(status, publishedAt, reviewRequestedAt, null);
        String validatedCaption = validateCaption(caption);

        return new CommunityPost(
                id,
                authorUserId,
                validatedCaption,
                null,
                imageMediaAssetId,
                status,
                0,
                createdAt,
                createdAt,
                publishedAt,
                reviewRequestedAt
        );
    }

    /**
     * Rehydrates a CommunityPost from persistence storage (without pendingCaption).
     */
    public static CommunityPost rehydrate(
            UUID id,
            UUID authorUserId,
            String caption,
            UUID imageMediaAssetId,
            CommunityPostStatus status,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant reviewRequestedAt
    ) {
        return new CommunityPost(
                id,
                authorUserId,
                caption,
                null,
                imageMediaAssetId,
                status,
                contentVersion,
                createdAt,
                updatedAt,
                publishedAt,
                reviewRequestedAt
        );
    }

    /**
     * Rehydrates a CommunityPost from persistence storage (with pendingCaption).
     */
    public static CommunityPost rehydrate(
            UUID id,
            UUID authorUserId,
            String caption,
            String pendingCaption,
            UUID imageMediaAssetId,
            CommunityPostStatus status,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant reviewRequestedAt
    ) {
        return new CommunityPost(
                id,
                authorUserId,
                caption,
                pendingCaption,
                imageMediaAssetId,
                status,
                contentVersion,
                createdAt,
                updatedAt,
                publishedAt,
                reviewRequestedAt
        );
    }

    /**
     * Edits the caption of this post.
     * Only PUBLISHED posts can be edited.
     *
     * @param actorUserId the user attempting the edit
     * @param newCaption the updated caption content
     * @param editedAt the timestamp of the edit
     * @param publicationMode the active runtime publication mode
     * @return {@code true} if the caption was changed, {@code false} if it was an idempotent no-op
     */
    public boolean editCaption(
            UUID actorUserId,
            String newCaption,
            Instant editedAt,
            CommunityPublicationMode publicationMode
    ) {
        if (actorUserId == null || !this.authorUserId.equals(actorUserId)) {
            throw new CommunityPostUnauthorizedException(actorUserId, this.id);
        }

        if (this.status != CommunityPostStatus.PUBLISHED) {
            throw new CommunityPostValidationException("Only PUBLISHED posts can be edited. Current status: " + this.status);
        }

        if (this.pendingCaption != null) {
            throw new CommunityPostPendingEditConflictException(this.id);
        }

        Objects.requireNonNull(publicationMode, "CommunityPublicationMode cannot be null.");
        String validatedNewCaption = validateCaption(newCaption);

        Objects.requireNonNull(editedAt, "Edit timestamp cannot be null.");
        if (editedAt.isBefore(this.updatedAt)) {
            throw new CommunityPostValidationException("Edit timestamp cannot be before the last updated timestamp.");
        }

        if (validatedNewCaption.equals(this.caption)) {
            return false;
        }

        if (publicationMode == CommunityPublicationMode.PRE_MODERATION) {
            this.pendingCaption = validatedNewCaption;
            this.reviewRequestedAt = editedAt;
            this.updatedAt = editedAt;
            // status remains PUBLISHED
            // caption remains current approved public caption
            // publishedAt is strictly preserved (never reset or erased)
            // contentVersion is NOT incremented until approved
        } else {
            this.caption = validatedNewCaption;
            this.pendingCaption = null;
            this.reviewRequestedAt = null;
            this.contentVersion++;
            this.updatedAt = editedAt;
            this.status = CommunityPostStatus.PUBLISHED;
        }
        return true;
    }

    /**
     * Transitions a post or pending edit to approved PUBLISHED state.
     *
     * @param transitionAt the timestamp of approval
     */
    public void approve(Instant transitionAt) {
        Objects.requireNonNull(transitionAt, "Transition timestamp cannot be null.");
        if (transitionAt.isBefore(this.updatedAt)) {
            throw new CommunityPostValidationException("Transition timestamp cannot be before the last updated timestamp.");
        }

        if (this.status == CommunityPostStatus.PENDING_REVIEW) {
            // Initial post approval
            this.status = CommunityPostStatus.PUBLISHED;
            if (this.publishedAt == null) {
                this.publishedAt = transitionAt;
            }
            this.reviewRequestedAt = null;
            this.pendingCaption = null;
            this.updatedAt = transitionAt;
        } else if (this.status == CommunityPostStatus.PUBLISHED && this.pendingCaption != null) {
            // Edit approval on already-published post: candidate promoted, contentVersion incremented
            this.caption = this.pendingCaption;
            this.pendingCaption = null;
            this.reviewRequestedAt = null;
            this.contentVersion++;
            this.updatedAt = transitionAt;
            // publishedAt preserved at original T0
        } else {
            throw new IllegalStateException("Cannot approve post with status: " + this.status + " and pendingCaption: " + this.pendingCaption);
        }
    }

    /**
     * Rejects a pending post or pending caption edit.
     *
     * @param transitionAt the timestamp of rejection
     */
    public void reject(Instant transitionAt) {
        Objects.requireNonNull(transitionAt, "Transition timestamp cannot be null.");
        if (transitionAt.isBefore(this.updatedAt)) {
            throw new CommunityPostValidationException("Transition timestamp cannot be before the last updated timestamp.");
        }

        if (this.status == CommunityPostStatus.PENDING_REVIEW) {
            // Initial post rejection
            this.status = CommunityPostStatus.REJECTED;
            this.reviewRequestedAt = null;
            this.pendingCaption = null;
            this.updatedAt = transitionAt;
        } else if (this.status == CommunityPostStatus.PUBLISHED && this.pendingCaption != null) {
            // Edit rejection on already-published post: candidate discarded, original caption remains
            this.pendingCaption = null;
            this.reviewRequestedAt = null;
            this.updatedAt = transitionAt;
            // status remains PUBLISHED
            // caption remains unchanged
            // contentVersion remains unchanged
            // publishedAt preserved at original T0
        } else {
            throw new IllegalStateException("Cannot reject post with status: " + this.status + " and pendingCaption: " + this.pendingCaption);
        }
    }

    /**
     * Transitions a post from PUBLISHED to HIDDEN.
     *
     * @param transitionAt the timestamp of hiding
     */
    public void hide(Instant transitionAt) {
        if (this.status != CommunityPostStatus.PUBLISHED) {
            throw new IllegalStateException("Cannot hide post with status: " + this.status);
        }
        Objects.requireNonNull(transitionAt, "Transition timestamp cannot be null.");
        if (transitionAt.isBefore(this.updatedAt)) {
            throw new CommunityPostValidationException("Transition timestamp cannot be before the last updated timestamp.");
        }
        this.status = CommunityPostStatus.HIDDEN;
        this.pendingCaption = null;
        this.reviewRequestedAt = null;
        // publishedAt preserved
        this.updatedAt = transitionAt;
    }

    /**
     * Transitions a post from HIDDEN to PUBLISHED.
     *
     * @param transitionAt the timestamp of restoration
     */
    public void restore(Instant transitionAt) {
        if (this.status != CommunityPostStatus.HIDDEN) {
            throw new IllegalStateException("Cannot restore post with status: " + this.status);
        }
        Objects.requireNonNull(transitionAt, "Transition timestamp cannot be null.");
        if (transitionAt.isBefore(this.updatedAt)) {
            throw new CommunityPostValidationException("Transition timestamp cannot be before the last updated timestamp.");
        }
        this.status = CommunityPostStatus.PUBLISHED;
        this.pendingCaption = null;
        this.reviewRequestedAt = null;
        // publishedAt preserved
        this.updatedAt = transitionAt;
    }

    private static void validateStatusInvariants(
            CommunityPostStatus status,
            Instant publishedAt,
            Instant reviewRequestedAt,
            String pendingCaption
    ) {
        Objects.requireNonNull(status, "Status cannot be null.");
        switch (status) {
            case PUBLISHED -> {
                if (publishedAt == null) {
                    throw new CommunityPostValidationException("PUBLISHED post must have a non-null publishedAt timestamp.");
                }
                if (pendingCaption == null && reviewRequestedAt != null) {
                    throw new CommunityPostValidationException("PUBLISHED post must have null reviewRequestedAt timestamp.");
                }
                if (pendingCaption != null && reviewRequestedAt == null) {
                    throw new CommunityPostValidationException("PUBLISHED post with pending edit must have non-null reviewRequestedAt timestamp.");
                }
            }
            case PENDING_REVIEW -> {
                if (reviewRequestedAt == null) {
                    throw new CommunityPostValidationException("PENDING_REVIEW post must have a non-null reviewRequestedAt timestamp.");
                }
                if (pendingCaption != null) {
                    throw new CommunityPostValidationException("PENDING_REVIEW post must have null pendingCaption.");
                }
            }
            case HIDDEN -> {
                if (publishedAt == null) {
                    throw new CommunityPostValidationException("HIDDEN post must have a non-null publishedAt timestamp.");
                }
                if (reviewRequestedAt != null) {
                    throw new CommunityPostValidationException("HIDDEN post must have null reviewRequestedAt timestamp.");
                }
                if (pendingCaption != null) {
                    throw new CommunityPostValidationException("HIDDEN post must have null pendingCaption.");
                }
            }
            case REJECTED -> {
                if (reviewRequestedAt != null) {
                    throw new CommunityPostValidationException("REJECTED post must have null reviewRequestedAt timestamp.");
                }
                if (pendingCaption != null) {
                    throw new CommunityPostValidationException("REJECTED post must have null pendingCaption.");
                }
            }
        }
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

    /**
     * Canonical caption validator and normalizer shared across domain creation, editing, and anti-spam guard.
     *
     * @param rawCaption raw input caption
     * @return trimmed non-blank caption within max length
     */
    public static String validateAndNormalizeCaption(String rawCaption) {
        return validateCaption(rawCaption);
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

    public String getPendingCaption() {
        return pendingCaption;
    }

    public boolean isPendingCaptionEdit() {
        return this.status == CommunityPostStatus.PUBLISHED && this.pendingCaption != null;
    }

    public UUID getImageMediaAssetId() {
        return imageMediaAssetId;
    }

    public CommunityPostStatus getStatus() {
        return status;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getReviewRequestedAt() {
        return reviewRequestedAt;
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
                ", pendingCaption='" + (pendingCaption != null && pendingCaption.length() > 30 ? pendingCaption.substring(0, 30) + "..." : pendingCaption) + '\'' +
                ", imageMediaAssetId=" + imageMediaAssetId +
                ", status=" + status +
                ", contentVersion=" + contentVersion +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", publishedAt=" + publishedAt +
                ", reviewRequestedAt=" + reviewRequestedAt +
                '}';
    }
}
