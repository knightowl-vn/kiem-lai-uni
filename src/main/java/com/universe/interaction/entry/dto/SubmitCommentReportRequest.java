package com.universe.interaction.entry.dto;

import com.universe.interaction.domain.report.ReportReason;

/**
 * Request DTO for submitting a report against an interaction comment.
 *
 * <p>Excludes client-supplied contextual values (actor ID, snapshot, timestamps, status),
 * which are resolved authoritatively on the server.
 */
public record SubmitCommentReportRequest(
        ReportReason reason,
        String description
) {
}
