package com.universe.interaction.entry.community.dto;

import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response DTO returned upon successful Community post report submission.
 *
 * <p>Excludes snapshot body, reporter identity, and resolution metadata to preserve privacy.
 */
public record CommunityPostReportResponseDTO(
        UUID reportId,
        ReportStatus status,
        Instant createdAt
) {

    public static CommunityPostReportResponseDTO from(InteractionReport report) {
        return new CommunityPostReportResponseDTO(
                report.getId(),
                report.getStatus(),
                report.getCreatedAt()
        );
    }
}
