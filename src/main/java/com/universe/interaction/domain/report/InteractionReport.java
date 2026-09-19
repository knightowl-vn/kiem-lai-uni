package com.universe.interaction.domain.report;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing a user report against an Interaction Comment.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Immutable scalar references to target comment and reporter user;</li>
 *   <li>Immutable snapshot evidence of the comment body at the moment of reporting;</li>
 *   <li>Taxonomy-based report reason with mandatory description for {@link ReportReason#OTHER};</li>
 *   <li>Normalized description (trimmed, max 500 characters, whitespace-only collapsed to null);</li>
 *   <li>Explicit three-state lifecycle: PENDING &rarr; RESOLVED_ACTION_TAKEN | RESOLVED_NO_ACTION;</li>
 *   <li>Terminal states are immutable and cannot transition again;</li>
 *   <li>Temporal integrity: resolution timestamp cannot precede report creation;</li>
 *   <li>Zero ORM/framework annotations; pure Java domain model;</li>
 *   <li>No {@code updatedAt} field.</li>
 * </ul>
 */
public final class InteractionReport {

    public static final int MAX_DESCRIPTION_LENGTH = 500;

    private final UUID id;
    private final UUID commentId;
    private final UUID reporterUserId;
    private final ReportReason reason;
    private final String description;
    private final String reportedBodySnapshot;
    private ReportStatus status;
    private final Instant createdAt;
    private UUID resolvedByUserId;
    private Instant resolvedAt;

    private InteractionReport(
            UUID id,
            UUID commentId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedBodySnapshot,
            ReportStatus status,
            Instant createdAt,
            UUID resolvedByUserId,
            Instant resolvedAt
    ) {
        this.id = Objects.requireNonNull(id, "Report ID cannot be null.");
        this.commentId = Objects.requireNonNull(commentId, "Comment ID cannot be null.");
        this.reporterUserId = Objects.requireNonNull(reporterUserId, "Reporter user ID cannot be null.");
        this.reason = Objects.requireNonNull(reason, "Report reason cannot be null.");

        if (reportedBodySnapshot == null) {
            throw new IllegalArgumentException("Reported body snapshot cannot be null.");
        }
        String trimmedSnapshot = reportedBodySnapshot.trim();
        if (trimmedSnapshot.isEmpty()) {
            throw new IllegalArgumentException("Reported body snapshot cannot be blank.");
        }
        this.reportedBodySnapshot = trimmedSnapshot;

        String normalizedDesc = null;
        if (description != null) {
            String trimmedDesc = description.trim();
            if (!trimmedDesc.isEmpty()) {
                normalizedDesc = trimmedDesc;
            }
        }
        if (reason.requiresDescription() && normalizedDesc == null) {
            throw new IllegalArgumentException("Description is required when report reason is OTHER.");
        }
        if (normalizedDesc != null && normalizedDesc.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Report description exceeds maximum length of " + MAX_DESCRIPTION_LENGTH + " characters."
            );
        }
        this.description = normalizedDesc;

        this.status = Objects.requireNonNull(status, "Report status cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");

        if (this.status == ReportStatus.PENDING) {
            if (resolvedByUserId != null) {
                throw new IllegalArgumentException("ResolvedByUserId must be null for a PENDING report.");
            }
            if (resolvedAt != null) {
                throw new IllegalArgumentException("ResolvedAt must be null for a PENDING report.");
            }
        } else {
            if (resolvedByUserId == null) {
                throw new IllegalArgumentException("ResolvedByUserId cannot be null for a resolved report.");
            }
            if (resolvedAt == null) {
                throw new IllegalArgumentException("ResolvedAt cannot be null for a resolved report.");
            }
            if (resolvedAt.isBefore(this.createdAt)) {
                throw new IllegalArgumentException("ResolvedAt timestamp cannot be before createdAt timestamp.");
            }
        }
        this.resolvedByUserId = resolvedByUserId;
        this.resolvedAt = resolvedAt;
    }

    /**
     * Factory method to create a new PENDING report.
     */
    public static InteractionReport createPending(
            UUID id,
            UUID commentId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedBodySnapshot,
            Instant createdAt
    ) {
        return new InteractionReport(
                id,
                commentId,
                reporterUserId,
                reason,
                description,
                reportedBodySnapshot,
                ReportStatus.PENDING,
                createdAt,
                null,
                null
        );
    }

    /**
     * Reconstitutes an existing report from persistence.
     */
    public static InteractionReport reconstitute(
            UUID id,
            UUID commentId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedBodySnapshot,
            ReportStatus status,
            Instant createdAt,
            UUID resolvedByUserId,
            Instant resolvedAt
    ) {
        return new InteractionReport(
                id,
                commentId,
                reporterUserId,
                reason,
                description,
                reportedBodySnapshot,
                status,
                createdAt,
                resolvedByUserId,
                resolvedAt
        );
    }

    /**
     * Resolves the report with action taken (e.g. comment moderated or deleted).
     */
    public void resolveActionTaken(UUID resolverUserId, Instant resolvedAt) {
        ensurePending();
        validateResolutionArguments(resolverUserId, resolvedAt);
        this.status = ReportStatus.RESOLVED_ACTION_TAKEN;
        this.resolvedByUserId = resolverUserId;
        this.resolvedAt = resolvedAt;
    }

    /**
     * Resolves the report with no action (e.g. deemed not violating policies).
     */
    public void resolveNoAction(UUID resolverUserId, Instant resolvedAt) {
        ensurePending();
        validateResolutionArguments(resolverUserId, resolvedAt);
        this.status = ReportStatus.RESOLVED_NO_ACTION;
        this.resolvedByUserId = resolverUserId;
        this.resolvedAt = resolvedAt;
    }

    private void ensurePending() {
        if (!this.status.isPending()) {
            throw new IllegalStateException("Cannot resolve report in terminal status: " + this.status);
        }
    }

    private void validateResolutionArguments(UUID resolverUserId, Instant resolvedAt) {
        if (resolverUserId == null) {
            throw new IllegalArgumentException("Resolver user ID cannot be null.");
        }
        if (resolvedAt == null) {
            throw new IllegalArgumentException("ResolvedAt timestamp cannot be null.");
        }
        if (resolvedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("ResolvedAt timestamp cannot be before createdAt timestamp.");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCommentId() {
        return commentId;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    public ReportReason getReason() {
        return reason;
    }

    public String getDescription() {
        return description;
    }

    public String getReportedBodySnapshot() {
        return reportedBodySnapshot;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getResolvedByUserId() {
        return resolvedByUserId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public boolean isPending() {
        return status.isPending();
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InteractionReport that = (InteractionReport) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "InteractionReport{" +
                "id=" + id +
                ", commentId=" + commentId +
                ", reporterUserId=" + reporterUserId +
                ", reason=" + reason +
                ", description='" + (description != null ? "[PROTECTED]" : "null") + '\'' +
                ", reportedBodySnapshot='" + (reportedBodySnapshot != null ? "[PROTECTED]" : "null") + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", resolvedByUserId=" + resolvedByUserId +
                ", resolvedAt=" + resolvedAt +
                '}';
    }
}
