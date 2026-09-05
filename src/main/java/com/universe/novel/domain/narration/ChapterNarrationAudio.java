package com.universe.novel.domain.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing the Novel-owned assignment between a chapter narration segment,
 * a managed voice, and its synthesized Media audio asset.
 * <p>
 * Core Invariants:
 * <ul>
 *     <li>{@code segmentId} and {@code managedVoiceId} are required and immutable.</li>
 *     <li>{@code mediaAssetId} is required (scalar reference to a Media platform asset).</li>
 *     <li>{@code generatedSynthesisRevision} must be &gt;= 1.</li>
 *     <li>One logical assignment exists per (segmentId, managedVoiceId) pair.</li>
 *     <li>Audio is compatible and reusable if and only if its {@code generatedSynthesisRevision}
 *         equals the current voice {@code synthesisRevision}.</li>
 * </ul>
 */
public class ChapterNarrationAudio {

    private final UUID id;
    private final UUID segmentId;
    private final UUID managedVoiceId;
    private UUID mediaAssetId;
    private long generatedSynthesisRevision;
    private final Instant createdAt;
    private Instant updatedAt;

    private ChapterNarrationAudio(
            UUID id,
            UUID segmentId,
            UUID managedVoiceId,
            UUID mediaAssetId,
            long generatedSynthesisRevision,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID audio phân đoạn không được để trống.");
        this.segmentId = Objects.requireNonNull(segmentId, "ID phân đoạn không được để trống.");
        this.managedVoiceId = Objects.requireNonNull(managedVoiceId, "ID giọng đọc không được để trống.");
        this.mediaAssetId = Objects.requireNonNull(mediaAssetId, "ID media asset không được để trống.");
        this.generatedSynthesisRevision = validateSynthesisRevision(generatedSynthesisRevision);
        this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");

        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Thời gian cập nhật không được trước thời gian tạo.");
        }
    }

    /**
     * Factory method to create a new narration audio assignment.
     */
    public static ChapterNarrationAudio create(
            UUID id,
            UUID segmentId,
            UUID managedVoiceId,
            UUID mediaAssetId,
            long generatedSynthesisRevision,
            Instant now
    ) {
        Objects.requireNonNull(now, "Thời gian tạo không được để trống.");
        return new ChapterNarrationAudio(
                id,
                segmentId,
                managedVoiceId,
                mediaAssetId,
                generatedSynthesisRevision,
                now,
                now
        );
    }

    /**
     * Factory method to rehydrate an existing narration audio assignment from persistence.
     */
    public static ChapterNarrationAudio rehydrate(
            UUID id,
            UUID segmentId,
            UUID managedVoiceId,
            UUID mediaAssetId,
            long generatedSynthesisRevision,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new ChapterNarrationAudio(
                id,
                segmentId,
                managedVoiceId,
                mediaAssetId,
                generatedSynthesisRevision,
                createdAt,
                updatedAt
        );
    }

    /**
     * Checks whether this audio assignment is compatible with the supplied synthesis revision.
     *
     * @param currentSynthesisRevision the current synthesis revision of the voice
     * @return {@code true} if compatible; {@code false} if stale
     */
    public boolean isCompatibleWith(long currentSynthesisRevision) {
        return this.generatedSynthesisRevision == currentSynthesisRevision;
    }

    /**
     * Checks whether this audio assignment is compatible with the given managed voice.
     *
     * @param managedVoice the managed voice aggregate
     * @return {@code true} if compatible and belonging to the same voice; {@code false} otherwise
     */
    public boolean isCompatibleWith(ManagedVoice managedVoice) {
        if (managedVoice == null) {
            return false;
        }
        if (!this.managedVoiceId.equals(managedVoice.getId())) {
            return false;
        }
        return isCompatibleWith(managedVoice.getSynthesisRevision());
    }

    /**
     * Replaces the currently attached audio with a newly synthesized and stored Media asset.
     *
     * @param newMediaAssetId                 the new Media asset identity
     * @param newGeneratedSynthesisRevision the new generated synthesis revision (&gt;= 1)
     * @param now                             the timestamp of replacement
     */
    public void replaceSuccessfulAudio(
            UUID newMediaAssetId,
            long newGeneratedSynthesisRevision,
            Instant now
    ) {
        Objects.requireNonNull(newMediaAssetId, "ID media asset mới không được để trống.");
        Objects.requireNonNull(now, "Thời gian thay thế không được để trống.");

        long validatedRevision = validateSynthesisRevision(newGeneratedSynthesisRevision);

        if (now.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Thời gian thay thế không được trước thời gian tạo.");
        }

        this.mediaAssetId = newMediaAssetId;
        this.generatedSynthesisRevision = validatedRevision;
        this.updatedAt = now;
    }

    private static long validateSynthesisRevision(long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("Synthesis revision must be at least 1: " + revision);
        }
        return revision;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSegmentId() {
        return segmentId;
    }

    public UUID getManagedVoiceId() {
        return managedVoiceId;
    }

    public UUID getMediaAssetId() {
        return mediaAssetId;
    }

    public long getGeneratedSynthesisRevision() {
        return generatedSynthesisRevision;
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
        ChapterNarrationAudio that = (ChapterNarrationAudio) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ChapterNarrationAudio{" +
                "id=" + id +
                ", segmentId=" + segmentId +
                ", managedVoiceId=" + managedVoiceId +
                ", mediaAssetId=" + mediaAssetId +
                ", generatedSynthesisRevision=" + generatedSynthesisRevision +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
