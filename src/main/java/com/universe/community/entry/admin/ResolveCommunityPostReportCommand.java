package com.universe.community.entry.admin;

import com.universe.interaction.domain.report.ReportModerationAction;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for an administrator to resolve an interaction report filed against a Community post.
 */
public record ResolveCommunityPostReportCommand(
        UUID reportId,
        UUID moderatorUserId,
        ReportModerationAction action,
        String reason
) {
    public ResolveCommunityPostReportCommand {
        Objects.requireNonNull(reportId, "Report ID cannot be null.");
        Objects.requireNonNull(moderatorUserId, "Moderator user ID cannot be null.");
        Objects.requireNonNull(action, "Report moderation action cannot be null.");
        if (action != ReportModerationAction.CONTENT_HIDDEN && action != ReportModerationAction.NO_ACTION) {
            throw new IllegalArgumentException("Action must be CONTENT_HIDDEN or NO_ACTION for community post reports.");
        }
        reason = (reason != null && !reason.isBlank()) ? reason.trim() : null;
    }
}
