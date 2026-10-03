package com.universe.interaction.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity mapping the {@code interaction_reports} table.
 *
 * <p>Uses scalar string representations for UUIDs and enums to maintain Clean Architecture
 * boundaries, avoid ORM entity relationships and preserve scalar references.
 *
 * <p>Note: The database-level {@code pending_slot} column is a virtual/stored generated column
 * used strictly for database-enforced partial uniqueness; it is deliberately NOT mapped in JPA.
 */
@Entity
@Table(name = "interaction_reports")
public class InteractionReportJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "target_type",
            nullable = false,
            length = 40
    )
    private String targetType;

    @Column(
            name = "target_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String targetId;

    @Column(
            name = "reporter_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String reporterUserId;

    @Column(
            name = "reason",
            nullable = false,
            length = 40
    )
    private String reason;

    @Column(
            name = "description",
            length = 500
    )
    private String description;

    @Column(
            name = "content_snapshot",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String contentSnapshot;

    @Column(
            name = "evidence_media_asset_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String evidenceMediaAssetId;

    @Column(
            name = "status",
            nullable = false,
            length = 40
    )
    private String status;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(
            name = "resolved_by_user_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String resolvedByUserId;

    @Column(
            name = "resolved_at"
    )
    private Instant resolvedAt;

    @Column(
            name = "moderation_action",
            length = 40
    )
    private String moderationAction;

    @Column(
            name = "target_deleted_at"
    )
    private Instant targetDeletedAt;

    protected InteractionReportJpaEntity() {
    }

    public InteractionReportJpaEntity(
            String id,
            String targetType,
            String targetId,
            String reporterUserId,
            String reason,
            String description,
            String contentSnapshot,
            String evidenceMediaAssetId,
            String status,
            Instant createdAt,
            String resolvedByUserId,
            Instant resolvedAt,
            String moderationAction,
            Instant targetDeletedAt
    ) {
        this.id = id;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.description = description;
        this.contentSnapshot = contentSnapshot;
        this.evidenceMediaAssetId = evidenceMediaAssetId;
        this.status = status;
        this.createdAt = createdAt;
        this.resolvedByUserId = resolvedByUserId;
        this.resolvedAt = resolvedAt;
        this.moderationAction = moderationAction;
        this.targetDeletedAt = targetDeletedAt;
    }

    public InteractionReportJpaEntity(
            String id,
            String targetType,
            String targetId,
            String reporterUserId,
            String reason,
            String description,
            String contentSnapshot,
            String status,
            Instant createdAt,
            String resolvedByUserId,
            Instant resolvedAt,
            String moderationAction,
            Instant targetDeletedAt
    ) {
        this(
                id,
                targetType,
                targetId,
                reporterUserId,
                reason,
                description,
                contentSnapshot,
                null,
                status,
                createdAt,
                resolvedByUserId,
                resolvedAt,
                moderationAction,
                targetDeletedAt
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getReporterUserId() {
        return reporterUserId;
    }

    public void setReporterUserId(String reporterUserId) {
        this.reporterUserId = reporterUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContentSnapshot() {
        return contentSnapshot;
    }

    public void setContentSnapshot(String contentSnapshot) {
        this.contentSnapshot = contentSnapshot;
    }

    public String getEvidenceMediaAssetId() {
        return evidenceMediaAssetId;
    }

    public void setEvidenceMediaAssetId(String evidenceMediaAssetId) {
        this.evidenceMediaAssetId = evidenceMediaAssetId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getResolvedByUserId() {
        return resolvedByUserId;
    }

    public void setResolvedByUserId(String resolvedByUserId) {
        this.resolvedByUserId = resolvedByUserId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getModerationAction() {
        return moderationAction;
    }

    public void setModerationAction(String moderationAction) {
        this.moderationAction = moderationAction;
    }

    public Instant getTargetDeletedAt() {
        return targetDeletedAt;
    }

    public void setTargetDeletedAt(Instant targetDeletedAt) {
        this.targetDeletedAt = targetDeletedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InteractionReportJpaEntity that = (InteractionReportJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "InteractionReportJpaEntity{" +
                "id='" + id + '\'' +
                ", targetType='" + targetType + '\'' +
                ", targetId='" + targetId + '\'' +
                ", reporterUserId='" + reporterUserId + '\'' +
                ", reason='" + reason + '\'' +
                ", description='" + (description != null ? "[PROTECTED]" : "null") + '\'' +
                ", contentSnapshot='" + (contentSnapshot != null ? "[PROTECTED]" : "null") + '\'' +
                ", evidenceMediaAssetId='" + evidenceMediaAssetId + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                ", resolvedByUserId='" + resolvedByUserId + '\'' +
                ", resolvedAt=" + resolvedAt +
                ", moderationAction='" + moderationAction + '\'' +
                ", targetDeletedAt=" + targetDeletedAt +
                '}';
    }
}
