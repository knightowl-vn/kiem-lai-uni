package com.universe.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing a Media Asset in the Media Platform.
 *
 * Manages:
 * - Stable UUID identity across all versions;
 * - Immutable MediaType;
 * - Mutable MediaVisibility (PUBLIC, PRIVATE, RESTRICTED);
 * - Lifecycle state: ACTIVE -> ARCHIVED -> DELETED;
 * - Monotonic version increments (starting at 1).
 *
 * Invariant: New versions can only be registered when status == ACTIVE.
 */
public final class MediaAsset {

    private final UUID id;

    private final MediaType mediaType;

    private MediaVisibility visibility;

    private MediaAssetStatus status;

    private int currentVersionNumber;

    private final Instant createdAt;

    private Instant updatedAt;

    private MediaAsset(
            UUID id,
            MediaType mediaType,
            MediaVisibility visibility,
            MediaAssetStatus status,
            int currentVersionNumber,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "MediaAsset ID cannot be null."
                );

        this.mediaType =
                Objects.requireNonNull(
                        mediaType,
                        "MediaType cannot be null."
                );

        this.visibility =
                Objects.requireNonNull(
                        visibility,
                        "MediaVisibility cannot be null."
                );

        this.status =
                Objects.requireNonNull(
                        status,
                        "MediaAssetStatus cannot be null."
                );

        if (currentVersionNumber < 1) {
            throw new IllegalArgumentException(
                    "Current version number must be greater than or equal to 1."
            );
        }
        this.currentVersionNumber =
                currentVersionNumber;

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Creation timestamp cannot be null."
                );

        this.updatedAt =
                Objects.requireNonNull(
                        updatedAt,
                        "Update timestamp cannot be null."
                );

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "updatedAt cannot be before createdAt."
            );
        }
    }

    /**
     * Registers a new initial media asset.
     * Starts in ACTIVE status with currentVersionNumber = 1.
     */
    public static MediaAsset registerInitial(
            UUID id,
            MediaType mediaType,
            MediaVisibility visibility,
            Instant now
    ) {
        Objects.requireNonNull(
                now,
                "Timestamp cannot be null."
        );

        return new MediaAsset(
                id,
                mediaType,
                visibility,
                MediaAssetStatus.ACTIVE,
                1,
                now,
                now
        );
    }

    /**
     * Rehydrates an aggregate from persistence without business side effects.
     */
    public static MediaAsset rehydrate(
            UUID id,
            MediaType mediaType,
            MediaVisibility visibility,
            MediaAssetStatus status,
            int currentVersionNumber,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new MediaAsset(
                id,
                mediaType,
                visibility,
                status,
                currentVersionNumber,
                createdAt,
                updatedAt
        );
    }

    /**
     * Increments the version number for a newly prepared version snapshot.
     * Only permitted while status is ACTIVE.
     *
     * @param now Current timestamp
     * @return The new monotonically incremented currentVersionNumber
     */
    public int registerNextVersion(
            Instant now
    ) {
        if (status != MediaAssetStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot register a new version for a media asset with status: "
                            + status
            );
        }

        validateMutationTimestamp(now);

        this.currentVersionNumber++;
        this.updatedAt = now;
        return this.currentVersionNumber;
    }

    /**
     * Updates asset visibility (PUBLIC, PRIVATE, RESTRICTED).
     * Prohibited when status is DELETED.
     */
    public void changeVisibility(
            MediaVisibility newVisibility,
            Instant now
    ) {
        Objects.requireNonNull(
                newVisibility,
                "MediaVisibility cannot be null."
        );

        Objects.requireNonNull(
                now,
                "Timestamp cannot be null."
        );

        if (status == MediaAssetStatus.DELETED) {
            throw new IllegalStateException(
                    "Cannot change visibility on a DELETED media asset."
            );
        }

        if (this.visibility == newVisibility) {
            return;
        }

        validateMutationTimestamp(now);

        this.visibility = newVisibility;
        this.updatedAt = now;
    }

    /**
     * Moves the asset to ARCHIVED status.
     * Only permitted from ACTIVE status.
     */
    public void archive(
            Instant now
    ) {
        if (status == MediaAssetStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Media asset is already ARCHIVED."
            );
        }

        if (status == MediaAssetStatus.DELETED) {
            throw new IllegalStateException(
                    "Cannot archive a DELETED media asset."
            );
        }

        validateMutationTimestamp(now);

        this.status = MediaAssetStatus.ARCHIVED;
        this.updatedAt = now;
    }

    /**
     * Restores an ARCHIVED asset back to ACTIVE status.
     * Only permitted from ARCHIVED status.
     */
    public void restoreToActive(
            Instant now
    ) {
        if (status == MediaAssetStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Media asset is already ACTIVE."
            );
        }

        if (status == MediaAssetStatus.DELETED) {
            throw new IllegalStateException(
                    "Cannot restore a DELETED media asset."
            );
        }

        validateMutationTimestamp(now);

        this.status = MediaAssetStatus.ACTIVE;
        this.updatedAt = now;
    }

    /**
     * Marks the asset as DELETED (tombstone).
     * DELETED is terminal.
     */
    public void markDeleted(
            Instant now
    ) {
        if (status == MediaAssetStatus.DELETED) {
            throw new IllegalStateException(
                    "Media asset is already DELETED."
            );
        }

        validateMutationTimestamp(now);

        this.status = MediaAssetStatus.DELETED;
        this.updatedAt = now;
    }

    private void validateMutationTimestamp(
            Instant now
    ) {
        Objects.requireNonNull(
                now,
                "Timestamp cannot be null."
        );

        if (now.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException(
                    "Mutation timestamp cannot be before the last updated timestamp."
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public MediaVisibility getVisibility() {
        return visibility;
    }

    public MediaAssetStatus getStatus() {
        return status;
    }

    public int getCurrentVersionNumber() {
        return currentVersionNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return status == MediaAssetStatus.ACTIVE;
    }

    public boolean isArchived() {
        return status == MediaAssetStatus.ARCHIVED;
    }

    public boolean isDeleted() {
        return status == MediaAssetStatus.DELETED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaAsset that = (MediaAsset) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MediaAsset{" +
                "id=" + id +
                ", mediaType=" + mediaType +
                ", visibility=" + visibility +
                ", status=" + status +
                ", currentVersionNumber=" + currentVersionNumber +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
