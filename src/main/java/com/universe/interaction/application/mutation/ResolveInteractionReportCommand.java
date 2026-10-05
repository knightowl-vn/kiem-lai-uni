package com.universe.interaction.application.mutation;

import com.universe.interaction.domain.report.ReportModerationAction;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to resolve an interaction report with a moderation action.
 */
public record ResolveInteractionReportCommand(
        UUID reportId,
        UUID moderatorUserId,
        ReportModerationAction action
) {
    public ResolveInteractionReportCommand {
        Objects.requireNonNull(reportId, "Report ID cannot be null.");
        Objects.requireNonNull(moderatorUserId, "Moderator user ID cannot be null.");
        Objects.requireNonNull(action, "Report moderation action cannot be null.");
        if (action != ReportModerationAction.CONTENT_HIDDEN && action != ReportModerationAction.NO_ACTION) {
            throw new IllegalArgumentException("Action must be CONTENT_HIDDEN or NO_ACTION for interaction report resolution.");
        }
    }
}
