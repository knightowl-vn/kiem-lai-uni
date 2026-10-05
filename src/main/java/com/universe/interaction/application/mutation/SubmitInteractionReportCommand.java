package com.universe.interaction.application.mutation;

import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportTargetType;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to submit a user report against a supported interaction target (COMMENT or COMMUNITY_POST).
 */
public record SubmitInteractionReportCommand(
        ReportTargetType targetType,
        UUID targetId,
        UUID reporterUserId,
        ReportReason reason,
        String description
) {

    public SubmitInteractionReportCommand {
        Objects.requireNonNull(targetType, "Report target type cannot be null.");
        Objects.requireNonNull(targetId, "Target ID cannot be null.");
        Objects.requireNonNull(reporterUserId, "Reporter user ID cannot be null.");
        Objects.requireNonNull(reason, "Report reason cannot be null.");
    }
}
