package com.universe.interaction.entry.admin.dto;

import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Presentation-ready structural data projection of a single comment report for Admin moderation.
 *
 * <p>Preserves clean architecture and separation of concerns:
 * <ul>
 *   <li>Contains no localized presentation labels, formatted dates, href URLs, or CSS classes;</li>
 *   <li>Retains authoritative historical report evidence;</li>
 *   <li>Exposes resolved user and target projections;</li>
 *   <li>Clearly distinguishes between immutable report snapshot and live comment state.</li>
 * </ul>
 */
public record AdminCommentReportDetailDTO(
        // Authoritative report evidence
        UUID reportId,
        UUID commentId,
        ReportReason reason,
        String description,
        String reportedBodySnapshot,
        ReportStatus status,
        Instant createdAt,

        // Reporter
        AdminCommentReportUserDTO reporter,

        // Resolution metadata
        UUID resolvedByUserId,
        AdminCommentReportUserDTO resolver,
        Instant resolvedAt,
        ReportModerationAction moderationAction,

        // Current comment state
        boolean currentCommentAvailable,
        String currentCommentBody,
        CommentStatus currentCommentStatus,
        Instant commentCreatedAt,
        Instant commentUpdatedAt,
        Instant commentDeletedAt,
        AdminCommentReportUserDTO commentAuthor,

        // Target content
        AdminCommentReportTargetDTO target
) {

    public AdminCommentReportDetailDTO {
        Objects.requireNonNull(reportId, "reportId cannot be null");
        Objects.requireNonNull(commentId, "commentId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(reportedBodySnapshot, "reportedBodySnapshot cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(reporter, "reporter cannot be null");
    }
}
