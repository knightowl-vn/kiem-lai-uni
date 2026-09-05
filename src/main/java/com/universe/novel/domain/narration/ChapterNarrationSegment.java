package com.universe.novel.domain.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted aggregate representing a chapter-scoped, voice-independent narration text segment.
 * <p>
 * A chapter narration segment is a deterministic speakable block of text derived from chapter Markdown.
 * Text, character count, and SHA-256 content hash are immutable for a given segment identity.
 * Content changes must produce a new segment identity rather than mutating an existing segment.
 * <p>
 * Core Domain Invariants:
 * <ul>
 *     <li>{@code id}, {@code chapterId}, {@code text}, {@code contentHash}, {@code status}, {@code createdAt}, and {@code updatedAt} are mandatory.</li>
 *     <li>{@code text}, {@code characterCount}, and {@code contentHash} are immutable for a segment identity.</li>
 *     <li>{@code contentHash} strictly matches the SHA-256 digest of {@code text}.</li>
 *     <li>{@code characterCount} equals {@code text.length()} and must be positive.</li>
 *     <li>{@code segmentIndex} must be non-negative (>= 0).</li>
 *     <li>A segment can be repositioned only while in {@link ChapterNarrationSegmentStatus#CURRENT} status.</li>
 *     <li>A {@link ChapterNarrationSegmentStatus#RETIRED} segment can be restored to {@link ChapterNarrationSegmentStatus#CURRENT} at a given index.</li>
 * </ul>
 */
public class ChapterNarrationSegment {

    private final UUID id;
    private final UUID chapterId;
    private int segmentIndex;
    private final String text;
    private final int characterCount;
    private final String contentHash;
    private ChapterNarrationSegmentStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    private ChapterNarrationSegment(
            UUID id,
            UUID chapterId,
            int segmentIndex,
            String text,
            int characterCount,
            String contentHash,
            ChapterNarrationSegmentStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID segment không được để trống.");
        this.chapterId = Objects.requireNonNull(chapterId, "ID chương không được để trống.");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("Chỉ số segment phải lớn hơn hoặc bằng 0: " + segmentIndex);
        }
        this.segmentIndex = segmentIndex;

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Nội dung segment không được để trống.");
        }
        this.text = text;

        if (characterCount != text.length() || characterCount <= 0) {
            throw new IllegalArgumentException("Độ dài ký tự phải khớp với độ dài thực tế của văn bản.");
        }
        this.characterCount = characterCount;

        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("Content hash không được để trống.");
        }
        String expectedHash = NarrationTextSegment.computeSha256(text);
        if (!contentHash.equals(expectedHash)) {
            throw new IllegalArgumentException("Content hash không khớp với mã băm SHA-256 của nội dung segment.");
        }
        this.contentHash = contentHash;

        this.status = Objects.requireNonNull(status, "Trạng thái segment không được để trống.");
        this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");
    }

    /**
     * Creates a new CURRENT narration segment with a precomputed hash.
     */
    public static ChapterNarrationSegment create(
            UUID id,
            UUID chapterId,
            int segmentIndex,
            String text,
            String contentHash,
            Instant now
    ) {
        Objects.requireNonNull(now, "Thời gian tạo không được để trống.");
        return new ChapterNarrationSegment(
                id,
                chapterId,
                segmentIndex,
                text,
                text != null ? text.length() : 0,
                contentHash,
                ChapterNarrationSegmentStatus.CURRENT,
                now,
                now
        );
    }

    /**
     * Creates a new CURRENT narration segment, computing the SHA-256 content hash automatically.
     */
    public static ChapterNarrationSegment create(
            UUID id,
            UUID chapterId,
            int segmentIndex,
            String text,
            Instant now
    ) {
        Objects.requireNonNull(text, "Nội dung segment không được để trống.");
        return create(id, chapterId, segmentIndex, text, NarrationTextSegment.computeSha256(text), now);
    }

    /**
     * Rehydrates an existing segment from persistent storage.
     */
    public static ChapterNarrationSegment rehydrate(
            UUID id,
            UUID chapterId,
            int segmentIndex,
            String text,
            int characterCount,
            String contentHash,
            ChapterNarrationSegmentStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new ChapterNarrationSegment(
                id,
                chapterId,
                segmentIndex,
                text,
                characterCount,
                contentHash,
                status,
                createdAt,
                updatedAt
        );
    }

    /**
     * Repositions a CURRENT segment to a new index.
     *
     * @param newIndex new 0-based ordered index
     * @param now      timestamp of change
     */
    public void reposition(int newIndex, Instant now) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        if (this.status != ChapterNarrationSegmentStatus.CURRENT) {
            throw new IllegalStateException("Chỉ segment ở trạng thái CURRENT mới có thể thay đổi vị trí.");
        }
        if (newIndex < 0) {
            throw new IllegalArgumentException("Chỉ số segment phải lớn hơn hoặc bằng 0: " + newIndex);
        }
        if (this.segmentIndex != newIndex) {
            this.segmentIndex = newIndex;
            this.updatedAt = now;
        }
    }

    /**
     * Retires an unmatched CURRENT segment.
     *
     * @param now timestamp of change
     */
    public void retire(Instant now) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        if (this.status != ChapterNarrationSegmentStatus.RETIRED) {
            this.status = ChapterNarrationSegmentStatus.RETIRED;
            this.updatedAt = now;
        }
    }

    /**
     * Restores a RETIRED segment at a new index.
     *
     * @param newIndex new 0-based ordered index
     * @param now      timestamp of change
     */
    public void restore(int newIndex, Instant now) {
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");
        if (this.status != ChapterNarrationSegmentStatus.RETIRED) {
            throw new IllegalStateException("Chỉ có thể khôi phục segment ở trạng thái RETIRED.");
        }
        if (newIndex < 0) {
            throw new IllegalArgumentException("Chỉ số segment phải lớn hơn hoặc bằng 0: " + newIndex);
        }
        this.status = ChapterNarrationSegmentStatus.CURRENT;
        this.segmentIndex = newIndex;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChapterId() {
        return chapterId;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public String getText() {
        return text;
    }

    public int getCharacterCount() {
        return characterCount;
    }

    public String getContentHash() {
        return contentHash;
    }

    public ChapterNarrationSegmentStatus getStatus() {
        return status;
    }

    public boolean isCurrent() {
        return status == ChapterNarrationSegmentStatus.CURRENT;
    }

    public boolean isRetired() {
        return status == ChapterNarrationSegmentStatus.RETIRED;
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
        ChapterNarrationSegment that = (ChapterNarrationSegment) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ChapterNarrationSegment{" +
                "id=" + id +
                ", chapterId=" + chapterId +
                ", segmentIndex=" + segmentIndex +
                ", characterCount=" + characterCount +
                ", contentHash='" + contentHash + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
