package com.universe.novel.domain.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an intent to clean up an obsolete or unreferenced
 * Media asset in the KiemLai Media module (MS-04.9H.8D1A).
 * <p>
 * Core Domain Invariants:
 * <ul>
 *     <li>{@code id}, {@code mediaAssetId}, {@code reason}, {@code createdAt}, and {@code updatedAt} are mandatory and immutable/valid.</li>
 *     <li>{@code attemptCount} starts at 0 and increments upon recording failed cleanup attempts.</li>
 *     <li>{@code updatedAt} must never be before {@code createdAt}.</li>
 *     <li>{@code lastAttemptAt} if present must never be before {@code createdAt}.</li>
 *     <li>{@code lastErrorType} is bounded (max 200 characters) and safe (no raw stack traces).</li>
 * </ul>
 */
public class NarrationMediaCleanupTask {

    public static final int MAX_ERROR_TYPE_LENGTH = 200;

    private final UUID id;
    private final UUID mediaAssetId;
    private final NarrationMediaCleanupReason reason;
    private int attemptCount;
    private String lastErrorType;
    private final Instant createdAt;
    private Instant lastAttemptAt;
    private Instant updatedAt;

    private NarrationMediaCleanupTask(
            UUID id,
            UUID mediaAssetId,
            NarrationMediaCleanupReason reason,
            int attemptCount,
            String lastErrorType,
            Instant createdAt,
            Instant lastAttemptAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID tác vụ dọn dẹp media không được để trống.");
        this.mediaAssetId = Objects.requireNonNull(mediaAssetId, "ID media asset không được để trống.");
        this.reason = Objects.requireNonNull(reason, "Lý do dọn dẹp media không được để trống.");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount không được âm: " + attemptCount);
        }
        this.attemptCount = attemptCount;
        this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");

        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Thời gian cập nhật không được trước thời gian tạo.");
        }

        if (lastAttemptAt != null && lastAttemptAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Thời gian thử gần nhất không được trước thời gian tạo.");
        }

        this.lastAttemptAt = lastAttemptAt;
        this.lastErrorType = sanitizeErrorType(lastErrorType);
    }

    /**
     * Factory method to create a new narration media cleanup task.
     */
    public static NarrationMediaCleanupTask create(
            UUID id,
            UUID mediaAssetId,
            NarrationMediaCleanupReason reason,
            Instant now
    ) {
        Objects.requireNonNull(now, "Thời gian tạo không được để trống.");
        return new NarrationMediaCleanupTask(
                id,
                mediaAssetId,
                reason,
                0,
                null,
                now,
                null,
                now
        );
    }

    /**
     * Factory method to rehydrate an existing narration media cleanup task from persistence storage.
     */
    public static NarrationMediaCleanupTask rehydrate(
            UUID id,
            UUID mediaAssetId,
            NarrationMediaCleanupReason reason,
            int attemptCount,
            String lastErrorType,
            Instant createdAt,
            Instant lastAttemptAt,
            Instant updatedAt
    ) {
        return new NarrationMediaCleanupTask(
                id,
                mediaAssetId,
                reason,
                attemptCount,
                lastErrorType,
                createdAt,
                lastAttemptAt,
                updatedAt
        );
    }

    /**
     * Records a failed cleanup attempt, incrementing the attempt count and updating attempt timestamps.
     *
     * @param errorType   the sanitized error type / exception name
     * @param attemptTime the timestamp of the failed attempt
     */
    public void recordFailedAttempt(String errorType, Instant attemptTime) {
        Objects.requireNonNull(attemptTime, "Thời gian thử không được để trống.");
        if (attemptTime.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Thời gian thử không được trước thời gian tạo.");
        }
        if (this.lastAttemptAt != null && attemptTime.isBefore(this.lastAttemptAt)) {
            throw new IllegalArgumentException("Thời gian thử mới không được trước thời gian thử trước đó.");
        }

        this.attemptCount++;
        this.lastErrorType = sanitizeErrorType(errorType);
        this.lastAttemptAt = attemptTime;
        this.updatedAt = attemptTime;
    }

    /**
     * Records a failed cleanup attempt from a thrown exception.
     *
     * @param throwable   the throwable that caused the failure
     * @param attemptTime the timestamp of the failed attempt
     */
    public void recordFailedAttempt(Throwable throwable, Instant attemptTime) {
        String errorType = sanitizeErrorType(throwable);
        recordFailedAttempt(errorType, attemptTime);
    }

    /**
     * Sanitizes a throwable into a bounded, safe error type name.
     */
    public static String sanitizeErrorType(Throwable throwable) {
        if (throwable == null) {
            return "UnknownError";
        }
        String simpleName = throwable.getClass().getSimpleName();
        if (simpleName != null && !simpleName.isBlank()) {
            return sanitizeErrorType(simpleName);
        }
        String name = throwable.getClass().getName();
        return sanitizeErrorType(name);
    }

    /**
     * Sanitizes a raw error type string into a trimmed, bounded type token.
     * Prevents persisting stack traces, multiline text, or raw provider messages.
     */
    public static String sanitizeErrorType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();

        // 1. Strip everything after the first newline / carriage return
        int newlineIdx = trimmed.indexOf('\n');
        if (newlineIdx >= 0) {
            trimmed = trimmed.substring(0, newlineIdx).trim();
        }
        int crIdx = trimmed.indexOf('\r');
        if (crIdx >= 0) {
            trimmed = trimmed.substring(0, crIdx).trim();
        }

        // 2. Strip message suffix if delimited by colon (e.g. "com.foo.Exception: Failed to connect" -> "com.foo.Exception")
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx >= 0) {
            trimmed = trimmed.substring(0, colonIdx).trim();
        }

        // 3. Take only the first token before whitespace/tabs
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx >= 0) {
            trimmed = trimmed.substring(0, spaceIdx).trim();
        }
        int tabIdx = trimmed.indexOf('\t');
        if (tabIdx >= 0) {
            trimmed = trimmed.substring(0, tabIdx).trim();
        }

        // 4. Retain only valid identifier/token characters [a-zA-Z0-9_.-]
        trimmed = trimmed.replaceAll("[^a-zA-Z0-9_.-]", "");

        if (trimmed.isBlank()) {
            return null;
        }

        if (trimmed.length() <= MAX_ERROR_TYPE_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_ERROR_TYPE_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMediaAssetId() {
        return mediaAssetId;
    }

    public NarrationMediaCleanupReason getReason() {
        return reason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastErrorType() {
        return lastErrorType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NarrationMediaCleanupTask that = (NarrationMediaCleanupTask) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "NarrationMediaCleanupTask{" +
                "id=" + id +
                ", mediaAssetId=" + mediaAssetId +
                ", reason=" + reason +
                ", attemptCount=" + attemptCount +
                ", lastErrorType='" + lastErrorType + '\'' +
                ", createdAt=" + createdAt +
                ", lastAttemptAt=" + lastAttemptAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
