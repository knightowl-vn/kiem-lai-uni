package com.universe.interaction.application.mutation;

import com.universe.interaction.domain.report.ReportReason;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to submit a report against an interaction comment.
 *
 * <p>All contextual attributes (reported comment body snapshot, comment status, target eligibility,
 * and creation timestamp) are resolved authoritatively on the server to prevent client spoofing.
 */
public record SubmitCommentReportCommand(
        UUID commentId,
        UUID reporterUserId,
        ReportReason reason,
        String description
) {

    public SubmitCommentReportCommand {
        Objects.requireNonNull(commentId, "Comment ID cannot be null.");
        Objects.requireNonNull(reporterUserId, "Reporter user ID cannot be null.");
        Objects.requireNonNull(reason, "Report reason cannot be null.");
    }
}
