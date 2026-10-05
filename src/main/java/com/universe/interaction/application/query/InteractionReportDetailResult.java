package com.universe.interaction.application.query;

import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable application result representing raw, consumer-neutral details for an interaction report.
 *
 * <p>Preserves clean architecture boundaries:
 * <ul>
 *   <li>Exposes report evidence and lifecycle state generically for any supported target;</li>
 *   <li>Maintains explicit separation between immutable historical evidence ({@code reportedContentSnapshot})
 *       and optional live target state;</li>
 *   <li>Explicitly indicates live comment availability via {@code liveCommentAvailable} when reporting comments.</li>
 * </ul>
 */
public record InteractionReportDetailResult(
        // Report fields (authoritative & immutable)
        UUID reportId,
        ReportTargetType reportTargetType,
        UUID reportTargetId,
        UUID reporterUserId,
        ReportReason reason,
        String description,
        String reportedContentSnapshot,
        ReportStatus status,
        Instant createdAt,
        UUID resolvedByUserId,
        Instant resolvedAt,
        ReportModerationAction moderationAction,
        Instant targetDeletedAt,

        // Optional live comment enrichment (when reportTargetType == COMMENT and comment is available)
        boolean liveCommentAvailable,
        UUID commentAuthorUserId,
        CommentStatus currentCommentStatus,
        String currentCommentBody,
        CommentTargetType contentTargetType,
        UUID contentTargetId,
        UUID currentCommentThreadRootCommentId,
        Instant commentCreatedAt,
        Instant commentUpdatedAt,
        Instant commentDeletedAt
) {

    public InteractionReportDetailResult {
        Objects.requireNonNull(reportId, "reportId cannot be null");
        Objects.requireNonNull(reportTargetType, "reportTargetType cannot be null");
        Objects.requireNonNull(reportTargetId, "reportTargetId cannot be null");
        Objects.requireNonNull(reporterUserId, "reporterUserId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(reportedContentSnapshot, "reportedContentSnapshot cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }

    // Backward-compatibility accessors
    public UUID commentId() { return reportTargetId; }
    public String reportedBodySnapshot() { return reportedContentSnapshot; }
    public boolean currentCommentAvailable() { return liveCommentAvailable; }
    public CommentTargetType targetType() { return contentTargetType; }
    public UUID targetId() { return contentTargetId; }

    public static InteractionReportDetailResult forCommentWithLiveComment(
            InteractionReport report,
            Comment comment
    ) {
        Objects.requireNonNull(report, "report cannot be null");
        Objects.requireNonNull(comment, "comment cannot be null");

        return new InteractionReportDetailResult(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReporterUserId(),
                report.getReason(),
                report.getDescription(),
                report.getReportedContentSnapshot(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getResolvedByUserId(),
                report.getResolvedAt(),
                report.getModerationAction(),
                report.getTargetDeletedAt(),
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

    public static InteractionReportDetailResult forCommentWithoutLiveComment(InteractionReport report) {
        Objects.requireNonNull(report, "report cannot be null");

        return new InteractionReportDetailResult(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReporterUserId(),
                report.getReason(),
                report.getDescription(),
                report.getReportedContentSnapshot(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getResolvedByUserId(),
                report.getResolvedAt(),
                report.getModerationAction(),
                report.getTargetDeletedAt(),
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

    public static InteractionReportDetailResult forCommunityPost(InteractionReport report) {
        Objects.requireNonNull(report, "report cannot be null");

        return new InteractionReportDetailResult(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReporterUserId(),
                report.getReason(),
                report.getDescription(),
                report.getReportedContentSnapshot(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getResolvedByUserId(),
                report.getResolvedAt(),
                report.getModerationAction(),
                report.getTargetDeletedAt(),
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

    // Backward-compatible factory aliases
    public static InteractionReportDetailResult withAvailableComment(InteractionReport report, Comment comment) {
        return forCommentWithLiveComment(report, comment);
    }

    public static InteractionReportDetailResult withMissingComment(InteractionReport report) {
        return report.getTargetType() == ReportTargetType.COMMUNITY_POST
                ? forCommunityPost(report)
                : forCommentWithoutLiveComment(report);
    }
}
