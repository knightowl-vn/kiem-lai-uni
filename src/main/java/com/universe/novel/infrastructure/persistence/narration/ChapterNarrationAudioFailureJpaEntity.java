package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "novel_chapter_narration_audio_failures",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_chapter_narration_audio_failures_segment_voice",
                        columnNames = {"segment_id", "managed_voice_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_novel_chapter_narration_audio_failures_voice",
                        columnList = "managed_voice_id"
                )
        }
)
public class ChapterNarrationAudioFailureJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "segment_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String segmentId;

    @Column(
            name = "managed_voice_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String managedVoiceId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "operation",
            nullable = false,
            length = 50
    )
    private NarrationAudioOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "stage",
            nullable = false,
            length = 50
    )
    private NarrationAudioFailureStage stage;

    @Column(
            name = "attempted_synthesis_revision",
            nullable = false
    )
    private long attemptedSynthesisRevision;

    @Column(
            name = "failure_count",
            nullable = false
    )
    private int failureCount;

    @Column(
            name = "error_type",
            nullable = false,
            length = 200
    )
    private String errorType;

    @Column(
            name = "error_message",
            nullable = false,
            length = 1000
    )
    private String errorMessage;

    @Column(
            name = "first_failed_at",
            nullable = false
    )
    private Instant firstFailedAt;

    @Column(
            name = "last_failed_at",
            nullable = false
    )
    private Instant lastFailedAt;

    public ChapterNarrationAudioFailureJpaEntity() {
    }

    public ChapterNarrationAudioFailureJpaEntity(
            String id,
            String segmentId,
            String managedVoiceId,
            NarrationAudioOperation operation,
            NarrationAudioFailureStage stage,
            long attemptedSynthesisRevision,
            int failureCount,
            String errorType,
            String errorMessage,
            Instant firstFailedAt,
            Instant lastFailedAt
    ) {
        this.id = id;
        this.segmentId = segmentId;
        this.managedVoiceId = managedVoiceId;
        this.operation = operation;
        this.stage = stage;
        this.attemptedSynthesisRevision = attemptedSynthesisRevision;
        this.failureCount = failureCount;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.firstFailedAt = firstFailedAt;
        this.lastFailedAt = lastFailedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }

    public String getManagedVoiceId() {
        return managedVoiceId;
    }

    public void setManagedVoiceId(String managedVoiceId) {
        this.managedVoiceId = managedVoiceId;
    }

    public NarrationAudioOperation getOperation() {
        return operation;
    }

    public void setOperation(NarrationAudioOperation operation) {
        this.operation = operation;
    }

    public NarrationAudioFailureStage getStage() {
        return stage;
    }

    public void setStage(NarrationAudioFailureStage stage) {
        this.stage = stage;
    }

    public long getAttemptedSynthesisRevision() {
        return attemptedSynthesisRevision;
    }

    public void setAttemptedSynthesisRevision(long attemptedSynthesisRevision) {
        this.attemptedSynthesisRevision = attemptedSynthesisRevision;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getFirstFailedAt() {
        return firstFailedAt;
    }

    public void setFirstFailedAt(Instant firstFailedAt) {
        this.firstFailedAt = firstFailedAt;
    }

    public Instant getLastFailedAt() {
        return lastFailedAt;
    }

    public void setLastFailedAt(Instant lastFailedAt) {
        this.lastFailedAt = lastFailedAt;
    }
}
