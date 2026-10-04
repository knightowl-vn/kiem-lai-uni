package com.universe.community.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing the Community module runtime settings.
 */
public final class CommunitySettings {

    public static final String SINGLETON_ID = "DEFAULT";

    private final String id;
    private final CommunityPublicationMode publicationMode;
    private final long version;
    private final Instant updatedAt;
    private final UUID updatedByUserId;

    public CommunitySettings(
            String id,
            CommunityPublicationMode publicationMode,
            long version,
            Instant updatedAt,
            UUID updatedByUserId
    ) {
        if (!SINGLETON_ID.equals(id)) {
            throw new IllegalArgumentException("CommunitySettings id must be '" + SINGLETON_ID + "' but was: " + id);
        }
        this.id = id;
        this.publicationMode = Objects.requireNonNull(publicationMode, "PublicationMode cannot be null.");
        this.version = version;
        this.updatedAt = Objects.requireNonNull(updatedAt, "UpdatedAt cannot be null.");
        this.updatedByUserId = updatedByUserId;
    }

    public static CommunitySettings defaultSettings() {
        return new CommunitySettings(
                SINGLETON_ID,
                CommunityPublicationMode.AUTO_PUBLISH,
                0L,
                Instant.EPOCH,
                null
        );
    }

    public static CommunitySettings of(
            CommunityPublicationMode publicationMode,
            long version,
            Instant updatedAt,
            UUID updatedByUserId
    ) {
        return new CommunitySettings(
                SINGLETON_ID,
                publicationMode,
                version,
                updatedAt,
                updatedByUserId
        );
    }

    public CommunitySettings withPublicationMode(
            CommunityPublicationMode newMode,
            UUID actorUserId,
            Instant now
    ) {
        Objects.requireNonNull(actorUserId, "ActorUserId cannot be null.");
        return new CommunitySettings(
                SINGLETON_ID,
                newMode,
                this.version + 1,
                now,
                actorUserId
        );
    }

    public String getId() {
        return id;
    }

    public CommunityPublicationMode getPublicationMode() {
        return publicationMode;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedByUserId() {
        return updatedByUserId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommunitySettings that = (CommunitySettings) o;
        return version == that.version &&
                Objects.equals(id, that.id) &&
                publicationMode == that.publicationMode &&
                Objects.equals(updatedAt, that.updatedAt) &&
                Objects.equals(updatedByUserId, that.updatedByUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, publicationMode, version, updatedAt, updatedByUserId);
    }

    @Override
    public String toString() {
        return "CommunitySettings{" +
                "id='" + id + '\'' +
                ", publicationMode=" + publicationMode +
                ", version=" + version +
                ", updatedAt=" + updatedAt +
                ", updatedByUserId=" + updatedByUserId +
                '}';
    }
}
