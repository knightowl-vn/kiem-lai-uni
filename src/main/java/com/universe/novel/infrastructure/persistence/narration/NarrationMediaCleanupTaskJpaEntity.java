package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
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
        name = "novel_narration_media_cleanup_tasks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_narration_media_cleanup_tasks_media_asset",
                        columnNames = {"media_asset_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_novel_narration_media_cleanup_tasks_created_id",
                        columnList = "created_at, id"
                )
        }
)
public class NarrationMediaCleanupTaskJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "media_asset_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String mediaAssetId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "reason",
            nullable = false,
            length = 50
    )
    private NarrationMediaCleanupReason reason;

    @Column(
            name = "attempt_count",
            nullable = false
    )
    private int attemptCount;

    @Column(
            name = "last_error_type",
            length = 200
    )
    private String lastErrorType;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(
            name = "last_attempt_at"
    )
    private Instant lastAttemptAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    public NarrationMediaCleanupTaskJpaEntity() {
    }

    public NarrationMediaCleanupTaskJpaEntity(
            String id,
            String mediaAssetId,
            NarrationMediaCleanupReason reason,
            int attemptCount,
            String lastErrorType,
            Instant createdAt,
            Instant lastAttemptAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.mediaAssetId = mediaAssetId;
        this.reason = reason;
        this.attemptCount = attemptCount;
        this.lastErrorType = lastErrorType;
        this.createdAt = createdAt;
        this.lastAttemptAt = lastAttemptAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMediaAssetId() {
        return mediaAssetId;
    }

    public void setMediaAssetId(String mediaAssetId) {
        this.mediaAssetId = mediaAssetId;
    }

    public NarrationMediaCleanupReason getReason() {
        return reason;
    }

    public void setReason(NarrationMediaCleanupReason reason) {
        this.reason = reason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getLastErrorType() {
        return lastErrorType;
    }

    public void setLastErrorType(String lastErrorType) {
        this.lastErrorType = lastErrorType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
