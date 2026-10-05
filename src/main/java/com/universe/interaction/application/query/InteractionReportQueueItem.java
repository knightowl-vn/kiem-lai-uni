package com.universe.interaction.application.query;

import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Raw queue item projection holding report evidence, current comment metadata, and moderation audit state.
 *
 * <p>Framework-free, consumer-neutral, and presentation-free.
 *
 * @param reportId unique identifier of the report
 * @param reportTargetType target type of the report (COMMENT or COMMUNITY_POST)
 * @param reportTargetId unique identifier of the reported target
 * @param reporterUserId unique identifier of the reporting user
 * @param reason taxonomy report reason
 * @param description optional reporter-provided explanation (nullable)
 * @param reportedContentSnapshot immutable snapshot of target content captured at report submission time
 * @param status current lifecycle status of the report
 * @param createdAt timestamp when the report was created
 * @param commentAuthorUserId unique identifier of the author of the reported comment (nullable)
 * @param contentTargetType bounded-context content target type of the comment (nullable)
 * @param contentTargetId unique identifier of the comment's content target (nullable)
 * @param commentStatus current lifecycle state of the comment (nullable)
 * @param moderationAction exact persisted moderation action (null for PENDING, non-null for PROCESSED)
 * @param resolverUserId unique identifier of moderator resolving the report (null for PENDING, non-null for PROCESSED)
 * @param resolvedAt timestamp when report was resolved (null for PENDING, non-null for PROCESSED)
 * @param targetDeletedAt timestamp when the reported target was deleted (nullable)
 */
public record InteractionReportQueueItem(
        UUID reportId,
        ReportTargetType reportTargetType,
        UUID reportTargetId,
        UUID reporterUserId,
        ReportReason reason,
        String description,
        String reportedContentSnapshot,
        ReportStatus status,
        Instant createdAt,
        UUID commentAuthorUserId,
        CommentTargetType contentTargetType,
        UUID contentTargetId,
        CommentStatus commentStatus,
        ReportModerationAction moderationAction,
        UUID resolverUserId,
        Instant resolvedAt,
        Instant targetDeletedAt
) {
    public InteractionReportQueueItem {
        Objects.requireNonNull(reportId, "reportId cannot be null.");
        Objects.requireNonNull(reportTargetType, "reportTargetType cannot be null.");
        Objects.requireNonNull(reportTargetId, "reportTargetId cannot be null.");
        Objects.requireNonNull(reporterUserId, "reporterUserId cannot be null.");
        Objects.requireNonNull(reason, "reason cannot be null.");
        Objects.requireNonNull(reportedContentSnapshot, "reportedContentSnapshot cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");

        if (status == ReportStatus.PENDING) {
            if (moderationAction != null) {
                throw new IllegalArgumentException("moderationAction must be null for PENDING queue item.");
            }
            if (resolverUserId != null) {
                throw new IllegalArgumentException("resolverUserId must be null for PENDING queue item.");
            }
            if (resolvedAt != null) {
                throw new IllegalArgumentException("resolvedAt must be null for PENDING queue item.");
            }
        } else if (status == ReportStatus.RESOLVED_ACTION_TAKEN) {
            if (moderationAction != ReportModerationAction.DELETE_COMMENT) {
                throw new IllegalArgumentException("moderationAction must be DELETE_COMMENT for RESOLVED_ACTION_TAKEN queue item.");
            }
            Objects.requireNonNull(resolverUserId, "resolverUserId cannot be null for RESOLVED_ACTION_TAKEN queue item.");
            Objects.requireNonNull(resolvedAt, "resolvedAt cannot be null for RESOLVED_ACTION_TAKEN queue item.");
        } else if (status == ReportStatus.RESOLVED_NO_ACTION) {
            if (moderationAction != ReportModerationAction.NO_ACTION) {
                throw new IllegalArgumentException("moderationAction must be NO_ACTION for RESOLVED_NO_ACTION queue item.");
            }
            Objects.requireNonNull(resolverUserId, "resolverUserId cannot be null for RESOLVED_NO_ACTION queue item.");
            Objects.requireNonNull(resolvedAt, "resolvedAt cannot be null for RESOLVED_NO_ACTION queue item.");
        }
    }

    // Backward-compatibility accessors
    public UUID commentId() { return reportTargetId; }
    public String reportedBodySnapshot() { return reportedContentSnapshot; }
    public CommentTargetType targetType() { return contentTargetType; }
    public UUID targetId() { return contentTargetId; }
}
