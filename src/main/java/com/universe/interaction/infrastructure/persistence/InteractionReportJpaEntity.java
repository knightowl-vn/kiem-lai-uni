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
            name = "comment_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String commentId;

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
            name = "reported_body_snapshot",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String reportedBodySnapshot;

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

    protected InteractionReportJpaEntity() {
    }

    public InteractionReportJpaEntity(
            String id,
            String commentId,
            String reporterUserId,
            String reason,
            String description,
            String reportedBodySnapshot,
            String status,
            Instant createdAt,
            String resolvedByUserId,
            Instant resolvedAt
    ) {
        this.id = id;
        this.commentId = commentId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.description = description;
        this.reportedBodySnapshot = reportedBodySnapshot;
        this.status = status;
        this.createdAt = createdAt;
        this.resolvedByUserId = resolvedByUserId;
        this.resolvedAt = resolvedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
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

    public String getReportedBodySnapshot() {
        return reportedBodySnapshot;
    }

    public void setReportedBodySnapshot(String reportedBodySnapshot) {
        this.reportedBodySnapshot = reportedBodySnapshot;
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
                ", commentId='" + commentId + '\'' +
                ", reporterUserId='" + reporterUserId + '\'' +
                ", reason='" + reason + '\'' +
                ", description='" + (description != null ? "[PROTECTED]" : "null") + '\'' +
                ", reportedBodySnapshot='" + (reportedBodySnapshot != null ? "[PROTECTED]" : "null") + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                ", resolvedByUserId='" + resolvedByUserId + '\'' +
                ", resolvedAt=" + resolvedAt +
                '}';
    }
}
