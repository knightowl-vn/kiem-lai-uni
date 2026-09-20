package com.universe.interaction.application.query;

import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Raw queue item projection holding report evidence and current comment metadata.
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
        CommentStatus commentStatus
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
    }
}
