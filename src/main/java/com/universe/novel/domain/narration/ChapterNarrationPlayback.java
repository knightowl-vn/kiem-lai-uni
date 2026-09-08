package com.universe.novel.domain.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable Novel-owned playback identity for one chapter and managed voice.
 */
public class ChapterNarrationPlayback {

    private final UUID id;
    private final UUID chapterId;
    private final UUID managedVoiceId;
    private UUID currentArtifactId;
    private final Long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private ChapterNarrationPlayback(
            UUID id,
            UUID chapterId,
            UUID managedVoiceId,
            UUID currentArtifactId,
            Long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null.");
        this.chapterId = Objects.requireNonNull(chapterId, "chapterId must not be null.");
        this.managedVoiceId = Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null.");
        this.currentArtifactId = currentArtifactId;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null.");
        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt.");
        }
    }

    public static ChapterNarrationPlayback create(
            UUID id,
            UUID chapterId,
            UUID managedVoiceId,
            Instant now
    ) {
        Objects.requireNonNull(now, "now must not be null.");
        return new ChapterNarrationPlayback(
                id,
                chapterId,
                managedVoiceId,
                null,
                null,
                now,
                now
        );
    }

    public static ChapterNarrationPlayback rehydrate(
            UUID id,
            UUID chapterId,
            UUID managedVoiceId,
            UUID currentArtifactId,
            Long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new ChapterNarrationPlayback(
                id,
                chapterId,
                managedVoiceId,
                currentArtifactId,
                version,
                createdAt,
                updatedAt
        );
    }

    public void switchCurrentArtifact(ChapterNarrationPlaybackArtifact artifact, Instant now) {
        Objects.requireNonNull(artifact, "artifact must not be null.");
        Objects.requireNonNull(now, "now must not be null.");
        if (!this.id.equals(artifact.getPlaybackId())
                || !this.chapterId.equals(artifact.getChapterId())
                || !this.managedVoiceId.equals(artifact.getManagedVoiceId())) {
            throw new IllegalArgumentException("Artifact does not belong to this chapter narration playback.");
        }
        if (Objects.equals(this.currentArtifactId, artifact.getId())) {
            return;
        }
        if (now.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("now must not precede updatedAt.");
        }
        this.currentArtifactId = artifact.getId();
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChapterId() {
        return chapterId;
    }

    public UUID getManagedVoiceId() {
        return managedVoiceId;
    }

    public UUID getCurrentArtifactId() {
        return currentArtifactId;
    }

    public Long getVersion() {
        return version;
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
        ChapterNarrationPlayback that = (ChapterNarrationPlayback) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
