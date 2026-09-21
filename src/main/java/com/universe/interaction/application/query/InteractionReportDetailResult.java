package com.universe.interaction.application.query;

import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable application result representing raw, consumer-neutral details for an interaction report.
 *
 * <p>Preserves clean architecture boundaries:
 * <ul>
 *   <li>Exposes report evidence and current comment lifecycle state without cross-context display metadata;</li>
 *   <li>Maintains explicit separation between immutable historical evidence ({@code reportedBodySnapshot})
 *       and live comment body ({@code currentCommentBody});</li>
 *   <li>Explicitly indicates current comment availability via {@code currentCommentAvailable}.</li>
 * </ul>
 */
public record InteractionReportDetailResult(
        // Report fields (authoritative & immutable)
        UUID reportId,
        UUID commentId,
        UUID reporterUserId,
        ReportReason reason,
        String description,
        String reportedBodySnapshot,
        ReportStatus status,
        Instant createdAt,
        UUID resolvedByUserId,
        Instant resolvedAt,

        // Current comment state
        boolean currentCommentAvailable,
        UUID commentAuthorUserId,
        CommentStatus currentCommentStatus,
        String currentCommentBody,
        CommentTargetType targetType,
        UUID targetId,
        UUID currentCommentThreadRootCommentId,
        Instant commentCreatedAt,
        Instant commentUpdatedAt,
        Instant commentDeletedAt
) {

    public InteractionReportDetailResult {
        Objects.requireNonNull(reportId, "reportId cannot be null");
        Objects.requireNonNull(commentId, "commentId cannot be null");
        Objects.requireNonNull(reporterUserId, "reporterUserId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(reportedBodySnapshot, "reportedBodySnapshot cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }

    /**
     * Creates a detail result when the target comment is available (active or soft-deleted tombstone).
     */
    public static InteractionReportDetailResult withAvailableComment(
            InteractionReport report,
            Comment comment
    ) {
        Objects.requireNonNull(report, "report cannot be null");
        Objects.requireNonNull(comment, "comment cannot be null");

        return new InteractionReportDetailResult(
                report.getId(),
                report.getCommentId(),
                report.getReporterUserId(),
                report.getReason(),
                report.getDescription(),
                report.getReportedBodySnapshot(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getResolvedByUserId(),
                report.getResolvedAt(),
                true,
                comment.getAuthorUserId(),
                comment.getStatus(),
                comment.getBody(),
                comment.getTarget() != null ? comment.getTarget().type() : null,
                comment.getTarget() != null ? comment.getTarget().targetId() : null,
                comment.getThreadRootCommentId(),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                comment.getDeletedAt()
        );
    }

    /**
     * Creates a detail result when the target comment is missing / unavailable.
     * Historical report evidence is retained intact while comment current-state fields are null.
     */
    public static InteractionReportDetailResult withMissingComment(InteractionReport report) {
        Objects.requireNonNull(report, "report cannot be null");

        return new InteractionReportDetailResult(
                report.getId(),
                report.getCommentId(),
                report.getReporterUserId(),
                report.getReason(),
                report.getDescription(),
                report.getReportedBodySnapshot(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getResolvedByUserId(),
                report.getResolvedAt(),
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
