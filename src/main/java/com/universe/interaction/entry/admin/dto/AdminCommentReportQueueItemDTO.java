package com.universe.interaction.entry.admin.dto;

import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable composite item DTO for an entry in the Admin comment report queue.
 *
 * <p>Contains report evidence, current comment lifecycle state, enriched reporter
 * identity, enriched comment author identity, and enriched target display metadata.
 *
 * @param reportId unique identifier of the report
 * @param commentId unique identifier of the reported comment
 * @param reporter enriched reporter user metadata (never null)
 * @param reason taxonomy report reason (never null)
 * @param description optional reporter-provided explanation (nullable)
 * @param reportedBodySnapshot immutable snapshot of comment body at submission time (never null)
 * @param status current lifecycle status of the report (never null)
 * @param createdAt timestamp when the report was submitted (never null)
 * @param commentAuthor enriched comment author user metadata (never null)
 * @param commentStatus current lifecycle state of the comment (never null)
 * @param target enriched target display metadata (never null)
 */
public record AdminCommentReportQueueItemDTO(
        UUID reportId,
        UUID commentId,
        AdminCommentReportUserDTO reporter,
        ReportReason reason,
        String description,
        String reportedBodySnapshot,
        ReportStatus status,
        Instant createdAt,
        AdminCommentReportUserDTO commentAuthor,
        CommentStatus commentStatus,
        AdminCommentReportTargetDTO target
) {
    public AdminCommentReportQueueItemDTO {
        Objects.requireNonNull(reportId, "reportId cannot be null");
        Objects.requireNonNull(commentId, "commentId cannot be null");
        Objects.requireNonNull(reporter, "reporter cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(reportedBodySnapshot, "reportedBodySnapshot cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(commentAuthor, "commentAuthor cannot be null");
        Objects.requireNonNull(commentStatus, "commentStatus cannot be null");
        Objects.requireNonNull(target, "target cannot be null");
    }
}
