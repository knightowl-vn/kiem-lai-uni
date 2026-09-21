package com.universe.interaction.application.query;

import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Raw queue item projection holding report evidence, current comment metadata, and moderation audit state.
 *
 * <p>Framework-free, consumer-neutral, and presentation-free.
 *
 * @param reportId unique identifier of the report
 * @param commentId unique identifier of the reported comment
 * @param reporterUserId unique identifier of the reporting user
 * @param reason taxonomy report reason
 * @param description optional reporter-provided explanation (nullable)
 * @param reportedBodySnapshot immutable snapshot of comment body captured at report submission time
 * @param status current lifecycle status of the report
 * @param createdAt timestamp when the report was created
 * @param commentAuthorUserId unique identifier of the author of the reported comment
 * @param targetType bounded-context target type of the comment
 * @param targetId unique identifier of the comment's target
 * @param commentStatus current lifecycle state of the comment (e.g. ACTIVE, DELETED)
 * @param moderationAction exact persisted moderation action (null for PENDING, non-null for PROCESSED)
 * @param resolverUserId unique identifier of moderator resolving the report (null for PENDING, non-null for PROCESSED)
 * @param resolvedAt timestamp when report was resolved (null for PENDING, non-null for PROCESSED)
 */
public record InteractionReportQueueItem(
        UUID reportId,
        UUID commentId,
        UUID reporterUserId,
        ReportReason reason,
        String description,
        String reportedBodySnapshot,
        ReportStatus status,
        Instant createdAt,
        UUID commentAuthorUserId,
        CommentTargetType targetType,
        UUID targetId,
        CommentStatus commentStatus,
        ReportModerationAction moderationAction,
        UUID resolverUserId,
        Instant resolvedAt
) {
    public InteractionReportQueueItem {
        Objects.requireNonNull(reportId, "reportId cannot be null.");
        Objects.requireNonNull(commentId, "commentId cannot be null.");
        Objects.requireNonNull(reporterUserId, "reporterUserId cannot be null.");
        Objects.requireNonNull(reason, "reason cannot be null.");
        Objects.requireNonNull(reportedBodySnapshot, "reportedBodySnapshot cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(commentAuthorUserId, "commentAuthorUserId cannot be null.");
        Objects.requireNonNull(targetType, "targetType cannot be null.");
        Objects.requireNonNull(targetId, "targetId cannot be null.");
        Objects.requireNonNull(commentStatus, "commentStatus cannot be null.");

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
}
