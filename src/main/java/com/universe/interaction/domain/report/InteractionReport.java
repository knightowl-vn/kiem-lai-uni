package com.universe.interaction.domain.report;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing a user report against an Interaction content target (Comment, Community Post).
 *
 * <p>Invariants:
 * <ul>
 *   <li>Immutable scalar references to target (targetType + targetId) and reporter user;</li>
 *   <li>Immutable snapshot evidence of the content (comment body or post caption) at the moment of reporting;</li>
 *   <li>Taxonomy-based report reason with mandatory description for {@link ReportReason#OTHER};</li>
 *   <li>Normalized description (trimmed, max 500 characters, whitespace-only collapsed to null);</li>
 *   <li>Explicit three-state lifecycle: PENDING &rarr; RESOLVED_ACTION_TAKEN | RESOLVED_NO_ACTION;</li>
 *   <li>Terminal states are immutable and cannot transition again;</li>
 *   <li>Temporal integrity: resolution timestamp cannot precede report creation;</li>
 *   <li>Target deletion evidence retention: {@code targetDeletedAt} records physical deletion of target;</li>
 *   <li>Zero ORM/framework annotations; pure Java domain model.</li>
 * </ul>
 */
public final class InteractionReport {

    public static final int MAX_DESCRIPTION_LENGTH = 500;

    private final UUID id;
    private final ReportTargetType targetType;
    private final UUID targetId;
    private final UUID reporterUserId;
    private final ReportReason reason;
    private final String description;
    private final String reportedContentSnapshot;
    private ReportStatus status;
    private final Instant createdAt;
    private UUID resolvedByUserId;
    private Instant resolvedAt;
    private ReportModerationAction moderationAction;
    private Instant targetDeletedAt;

    private InteractionReport(
            UUID id,
            ReportTargetType targetType,
            UUID targetId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedContentSnapshot,
            ReportStatus status,
            Instant createdAt,
            UUID resolvedByUserId,
            Instant resolvedAt,
            ReportModerationAction moderationAction,
            Instant targetDeletedAt
    ) {
        this.id = Objects.requireNonNull(id, "Report ID cannot be null.");
        this.targetType = Objects.requireNonNull(targetType, "Report target type cannot be null.");
        this.targetId = Objects.requireNonNull(targetId, "Target ID cannot be null.");
        this.reporterUserId = Objects.requireNonNull(reporterUserId, "Reporter user ID cannot be null.");
        this.reason = Objects.requireNonNull(reason, "Report reason cannot be null.");

        if (reportedContentSnapshot == null) {
            throw new IllegalArgumentException("Reported content snapshot cannot be null.");
        }
        String trimmedSnapshot = reportedContentSnapshot.trim();
        if (trimmedSnapshot.isEmpty()) {
            throw new IllegalArgumentException("Reported content snapshot cannot be blank.");
        }
        this.reportedContentSnapshot = trimmedSnapshot;

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
            if (moderationAction != null) {
                throw new IllegalArgumentException("ModerationAction must be null for a PENDING report.");
            }
        } else if (this.status == ReportStatus.RESOLVED_ACTION_TAKEN) {
            if (resolvedByUserId == null) {
                throw new IllegalArgumentException("ResolvedByUserId cannot be null for a resolved report.");
            }
            if (resolvedAt == null) {
                throw new IllegalArgumentException("ResolvedAt cannot be null for a resolved report.");
            }
            if (resolvedAt.isBefore(this.createdAt)) {
                throw new IllegalArgumentException("ResolvedAt timestamp cannot be before createdAt timestamp.");
            }
            if (moderationAction != ReportModerationAction.DELETE_COMMENT) {
                throw new IllegalArgumentException("ModerationAction must be DELETE_COMMENT for RESOLVED_ACTION_TAKEN report.");
            }
        } else if (this.status == ReportStatus.RESOLVED_NO_ACTION) {
            if (resolvedByUserId == null) {
                throw new IllegalArgumentException("ResolvedByUserId cannot be null for a resolved report.");
            }
            if (resolvedAt == null) {
                throw new IllegalArgumentException("ResolvedAt cannot be null for a resolved report.");
            }
            if (resolvedAt.isBefore(this.createdAt)) {
                throw new IllegalArgumentException("ResolvedAt timestamp cannot be before createdAt timestamp.");
            }
            if (moderationAction != ReportModerationAction.NO_ACTION) {
                throw new IllegalArgumentException("ModerationAction must be NO_ACTION for RESOLVED_NO_ACTION report.");
            }
        } else {
            throw new IllegalArgumentException("Unsupported report status: " + this.status);
        }
        this.resolvedByUserId = resolvedByUserId;
        this.resolvedAt = resolvedAt;
        this.moderationAction = moderationAction;
        this.targetDeletedAt = targetDeletedAt;
    }

    /**
     * Factory method to create a new generic PENDING report.
     */
    public static InteractionReport createPending(
            UUID id,
            ReportTargetType targetType,
            UUID targetId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedContentSnapshot,
            Instant createdAt
    ) {
        return new InteractionReport(
                id,
                targetType,
                targetId,
                reporterUserId,
                reason,
                description,
                reportedContentSnapshot,
                ReportStatus.PENDING,
                createdAt,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Legacy factory method to create a new PENDING report targeting a comment.
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
        return createPending(
                id,
                ReportTargetType.COMMENT,
                commentId,
                reporterUserId,
                reason,
                description,
                reportedBodySnapshot,
                createdAt
        );
    }

    /**
     * Reconstitutes an existing report from persistence with all fields.
     */
    public static InteractionReport reconstitute(
            UUID id,
            ReportTargetType targetType,
            UUID targetId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedContentSnapshot,
            ReportStatus status,
            Instant createdAt,
            UUID resolvedByUserId,
            Instant resolvedAt,
            ReportModerationAction moderationAction,
            Instant targetDeletedAt
    ) {
        return new InteractionReport(
                id,
                targetType,
                targetId,
                reporterUserId,
                reason,
                description,
                reportedContentSnapshot,
                status,
                createdAt,
                resolvedByUserId,
                resolvedAt,
                moderationAction,
                targetDeletedAt
        );
    }

    /**
     * Reconstitutes an existing report from persistence without targetDeletedAt.
     */
    public static InteractionReport reconstitute(
            UUID id,
            ReportTargetType targetType,
            UUID targetId,
            UUID reporterUserId,
            ReportReason reason,
            String description,
            String reportedContentSnapshot,
            ReportStatus status,
            Instant createdAt,
            UUID resolvedByUserId,
            Instant resolvedAt,
            ReportModerationAction moderationAction
    ) {
        return reconstitute(
                id,
                targetType,
                targetId,
                reporterUserId,
                reason,
                description,
                reportedContentSnapshot,
                status,
                createdAt,
                resolvedByUserId,
                resolvedAt,
                moderationAction,
                null
        );
    }

    /**
     * Backward-compatible reconstitute overload for comment reports.
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
            Instant resolvedAt,
            ReportModerationAction moderationAction
    ) {
        return reconstitute(
                id,
                ReportTargetType.COMMENT,
                commentId,
                reporterUserId,
                reason,
                description,
                reportedBodySnapshot,
                status,
                createdAt,
                resolvedByUserId,
                resolvedAt,
                moderationAction,
                null
        );
    }

    /**
     * Resolves the report with action taken (comment deleted by moderation).
     */
    public void resolveActionTaken(UUID resolverUserId, Instant resolvedAt, ReportModerationAction moderationAction) {
        ensurePending();
        validateResolutionArguments(resolverUserId, resolvedAt);
        if (moderationAction != ReportModerationAction.DELETE_COMMENT) {
            throw new IllegalArgumentException("Action must be DELETE_COMMENT for resolveActionTaken.");
        }
        if (this.targetType != ReportTargetType.COMMENT) {
            throw new IllegalArgumentException("DELETE_COMMENT action is not supported for target type " + this.targetType);
        }
        this.status = ReportStatus.RESOLVED_ACTION_TAKEN;
        this.moderationAction = moderationAction;
        this.resolvedByUserId = resolverUserId;
        this.resolvedAt = resolvedAt;
    }

    /**
     * Resolves the report with action taken (defaulting to DELETE_COMMENT for COMMENT targets).
     */
    public void resolveActionTaken(UUID resolverUserId, Instant resolvedAt) {
        if (this.targetType != ReportTargetType.COMMENT) {
            throw new IllegalArgumentException("Cannot default resolution action for non-COMMENT target: " + this.targetType);
        }
        resolveActionTaken(resolverUserId, resolvedAt, ReportModerationAction.DELETE_COMMENT);
    }

    /**
     * Resolves the report with no action (e.g. deemed not violating policies).
     */
    public void resolveNoAction(UUID resolverUserId, Instant resolvedAt) {
        ensurePending();
        validateResolutionArguments(resolverUserId, resolvedAt);
        this.status = ReportStatus.RESOLVED_NO_ACTION;
        this.moderationAction = ReportModerationAction.NO_ACTION;
        this.resolvedByUserId = resolverUserId;
        this.resolvedAt = resolvedAt;
    }

    /**
     * Marks the target as physically deleted, recording the deletion timestamp as retention anchor.
     */
    public void markTargetDeleted(Instant targetDeletedAt) {
        if (targetDeletedAt == null) {
            throw new IllegalArgumentException("TargetDeletedAt cannot be null.");
        }
        this.targetDeletedAt = targetDeletedAt;
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

    public ReportTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
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

    public String getReportedContentSnapshot() {
        return reportedContentSnapshot;
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

    public ReportModerationAction getModerationAction() {
        return moderationAction;
    }

    public Instant getTargetDeletedAt() {
        return targetDeletedAt;
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
                ", targetType=" + targetType +
                ", targetId=" + targetId +
                ", reporterUserId=" + reporterUserId +
                ", reason=" + reason +
                ", description='" + (description != null ? "[PROTECTED]" : "null") + '\'' +
                ", reportedContentSnapshot='" + (reportedContentSnapshot != null ? "[PROTECTED]" : "null") + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", resolvedByUserId=" + resolvedByUserId +
                ", resolvedAt=" + resolvedAt +
                ", moderationAction=" + moderationAction +
                ", targetDeletedAt=" + targetDeletedAt +
                '}';
    }
}
