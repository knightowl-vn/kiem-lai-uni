package com.universe.interaction.entry.community.dto;

import com.universe.interaction.domain.report.ReportReason;

/**
 * Request payload for submitting a moderation report against a Community post.
 */
public record SubmitCommunityPostReportRequest(
        ReportReason reason,
        String description
) {
}
