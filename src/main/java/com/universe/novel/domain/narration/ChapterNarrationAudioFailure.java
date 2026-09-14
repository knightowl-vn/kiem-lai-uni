package com.universe.novel.domain.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain aggregate representing the latest unresolved generation failure record
 * for a chapter narration segment and managed voice pair.
 * <p>
 * Core Domain Invariants:
 * <ul>
 *     <li>{@code id}, {@code segmentId}, and {@code managedVoiceId} are mandatory and immutable.</li>
 *     <li>One logical record exists per (segmentId, managedVoiceId) pair.</li>
 *     <li>{@code attemptedSynthesisRevision} must be &gt;= 1.</li>
 *     <li>{@code failureCount} starts at 1 and increments on repeated failures.</li>
 *     <li>{@code firstFailedAt} is immutable; {@code lastFailedAt} is updated upon recording subsequent failures.</li>
 *     <li>{@code errorMessage} is bounded (max 1000 characters) and safe (no stack traces or secrets).</li>
 * </ul>
 */
public class ChapterNarrationAudioFailure {

    public static final int MAX_ERROR_TYPE_LENGTH = 200;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final UUID id;
    private final UUID segmentId;
    private final UUID managedVoiceId;
    private NarrationAudioOperation operation;
    private NarrationAudioFailureStage stage;
    private long attemptedSynthesisRevision;
    private int failureCount;
    private String errorType;
    private String errorMessage;
    private final Instant firstFailedAt;
    private Instant lastFailedAt;

    private ChapterNarrationAudioFailure(
            UUID id,
            UUID segmentId,
            UUID managedVoiceId,
            NarrationAudioOperation operation,
            NarrationAudioFailureStage stage,
            long attemptedSynthesisRevision,
            int failureCount,
            String errorType,
            String errorMessage,
            Instant firstFailedAt,
            Instant lastFailedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID bản ghi lỗi không được để trống.");
        this.segmentId = Objects.requireNonNull(segmentId, "ID phân đoạn không được để trống.");
        this.managedVoiceId = Objects.requireNonNull(managedVoiceId, "ID giọng đọc không được để trống.");
        this.operation = Objects.requireNonNull(operation, "Thao tác sinh audio không được để trống.");
        this.stage = Objects.requireNonNull(stage, "Giai đoạn phát sinh lỗi không được để trống.");
        this.attemptedSynthesisRevision = validateRevision(attemptedSynthesisRevision);
        this.failureCount = validateFailureCount(failureCount);
        this.errorType = validateErrorType(errorType);
        this.errorMessage = validateErrorMessage(errorMessage);
        this.firstFailedAt = Objects.requireNonNull(firstFailedAt, "Thời điểm lỗi đầu tiên không được để trống.");
        this.lastFailedAt = Objects.requireNonNull(lastFailedAt, "Thời điểm lỗi gần nhất không được để trống.");

        if (this.lastFailedAt.isBefore(this.firstFailedAt)) {
            throw new IllegalArgumentException("Thời điểm lỗi gần nhất không được trước thời điểm lỗi đầu tiên.");
        }
    }

    /**
     * Factory method to create a new failure record.
     * Derives bounded safe errorMessage directly from NarrationAudioFailureStage.
     */
    public static ChapterNarrationAudioFailure create(
            UUID id,
            UUID segmentId,
            UUID managedVoiceId,
            NarrationAudioOperation operation,
            NarrationAudioFailureStage stage,
            long attemptedSynthesisRevision,
            String errorType,
            Instant now
    ) {
        Objects.requireNonNull(now, "Thời gian ghi nhận lỗi không được để trống.");
        String safeErrorMessage = resolveSafeErrorMessage(stage);
        return new ChapterNarrationAudioFailure(
                id,
                segmentId,
                managedVoiceId,
                operation,
                stage,
                attemptedSynthesisRevision,
                1,
                errorType,
                safeErrorMessage,
                now,
                now
        );
    }

    /**
     * Factory method to rehydrate a failure record from persistence storage.
     */
    public static ChapterNarrationAudioFailure rehydrate(
            UUID id,
            UUID segmentId,
            UUID managedVoiceId,
            NarrationAudioOperation operation,
            NarrationAudioFailureStage stage,
            long attemptedSynthesisRevision,
            int failureCount,
            String errorType,
            String errorMessage,
            Instant firstFailedAt,
            Instant lastFailedAt
    ) {
        return new ChapterNarrationAudioFailure(
                id,
                segmentId,
                managedVoiceId,
                operation,
                stage,
                attemptedSynthesisRevision,
                failureCount,
                errorType,
                errorMessage,
                firstFailedAt,
                lastFailedAt
        );
    }

    /**
     * Records a subsequent failure for this segment/voice pair, incrementing the failure count.
     * Derives bounded safe errorMessage directly from NarrationAudioFailureStage.
     * <p>
     * All parameters are validated first before any internal state is mutated.
     */
    public void recordFailure(
            NarrationAudioOperation operation,
            NarrationAudioFailureStage stage,
            long attemptedSynthesisRevision,
            String errorType,
            Instant now
    ) {
        NarrationAudioOperation validatedOperation = Objects.requireNonNull(operation, "Thao tác sinh audio không được để trống.");
        NarrationAudioFailureStage validatedStage = Objects.requireNonNull(stage, "Giai đoạn phát sinh lỗi không được để trống.");
        long validatedRevision = validateRevision(attemptedSynthesisRevision);
        String validatedErrorType = validateErrorType(errorType);
        String safeErrorMessage = resolveSafeErrorMessage(validatedStage);
        Instant validatedNow = Objects.requireNonNull(now, "Thời gian ghi nhận lỗi không được để trống.");

        if (validatedNow.isBefore(this.lastFailedAt)) {
            throw new IllegalArgumentException("Thời điểm lỗi mới không được trước thời điểm lỗi gần nhất.");
        }

        this.operation = validatedOperation;
        this.stage = validatedStage;
        this.attemptedSynthesisRevision = validatedRevision;
        this.errorType = validatedErrorType;
        this.errorMessage = safeErrorMessage;
        this.failureCount++;
        this.lastFailedAt = validatedNow;
    }

    /**
     * Resolves a bounded, provider-neutral safe failure summary based on the failure stage.
     * Prevents persisting arbitrary exception messages, provider responses, or sensitive details.
     */
    public static String resolveSafeErrorMessage(NarrationAudioFailureStage stage) {
        if (stage == null) {
            return "Narration audio generation failed.";
        }
        return switch (stage) {
            case TTS_SYNTHESIS -> "Narration TTS synthesis failed.";
            case AUDIO_ENCODING -> "Narration audio encoding failed.";
            case MEDIA_UPLOAD -> "Narration audio media upload failed.";
            case ASSIGNMENT_PERSISTENCE -> "Narration audio assignment persistence failed.";
        };
    }

    public static String sanitizeErrorType(Throwable throwable) {
        if (throwable == null) {
            return "UnknownError";
        }
        String simpleName = throwable.getClass().getSimpleName();
        if (simpleName != null && !simpleName.isBlank()) {
            return simpleName.length() > MAX_ERROR_TYPE_LENGTH ? simpleName.substring(0, MAX_ERROR_TYPE_LENGTH) : simpleName;
        }
        String name = throwable.getClass().getName();
        return name.length() > MAX_ERROR_TYPE_LENGTH ? name.substring(0, MAX_ERROR_TYPE_LENGTH) : name;
    }

    private static long validateRevision(long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("attemptedSynthesisRevision phải >= 1: " + revision);
        }
        return revision;
    }

    private static int validateFailureCount(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("failureCount phải >= 1: " + count);
        }
        return count;
    }

    private static String validateErrorType(String errorType) {
        if (errorType == null || errorType.isBlank()) {
            throw new IllegalArgumentException("Loại lỗi không được để trống.");
        }
        String trimmed = errorType.trim();
        if (trimmed.length() > MAX_ERROR_TYPE_LENGTH) {
            return trimmed.substring(0, MAX_ERROR_TYPE_LENGTH);
        }
        return trimmed;
    }

    private static String validateErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("Thông điệp lỗi không được để trống.");
        }
        String trimmed = errorMessage.trim();
        if (trimmed.length() > MAX_ERROR_MESSAGE_LENGTH) {
            return trimmed.substring(0, MAX_ERROR_MESSAGE_LENGTH - 3) + "...";
        }
        return trimmed;
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

    public NarrationAudioOperation getOperation() {
        return operation;
    }

    public NarrationAudioFailureStage getStage() {
        return stage;
    }

    public long getAttemptedSynthesisRevision() {
        return attemptedSynthesisRevision;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getFirstFailedAt() {
        return firstFailedAt;
    }

    public Instant getLastFailedAt() {
        return lastFailedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChapterNarrationAudioFailure that = (ChapterNarrationAudioFailure) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ChapterNarrationAudioFailure{" +
                "id=" + id +
                ", segmentId=" + segmentId +
                ", managedVoiceId=" + managedVoiceId +
                ", operation=" + operation +
                ", stage=" + stage +
                ", attemptedSynthesisRevision=" + attemptedSynthesisRevision +
                ", failureCount=" + failureCount +
                ", errorType='" + errorType + '\'' +
                ", firstFailedAt=" + firstFailedAt +
                ", lastFailedAt=" + lastFailedAt +
                '}';
    }
}
