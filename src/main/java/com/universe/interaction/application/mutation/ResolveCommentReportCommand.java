package com.universe.interaction.application.mutation;

import com.universe.interaction.domain.report.ReportModerationAction;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to resolve an interaction comment report with a moderation action.
 */
public record ResolveCommentReportCommand(
        UUID reportId,
        UUID moderatorUserId,
        ReportModerationAction action
) {

    public ResolveCommentReportCommand {
        Objects.requireNonNull(reportId, "Report ID cannot be null.");
        Objects.requireNonNull(moderatorUserId, "Moderator user ID cannot be null.");
        Objects.requireNonNull(action, "Report moderation action cannot be null.");
    }
}
