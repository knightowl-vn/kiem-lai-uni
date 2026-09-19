package com.universe.interaction.entry.dto;

import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response DTO returned upon successful comment report submission.
 *
 * <p>Excludes snapshot body, reporter identity, and resolution metadata to preserve security and privacy.
 */
public record CommentReportResponseDTO(
        UUID reportId,
        ReportStatus status,
        Instant createdAt
) {

    public static CommentReportResponseDTO from(InteractionReport report) {
        return new CommentReportResponseDTO(
                report.getId(),
                report.getStatus(),
                report.getCreatedAt()
        );
    }
}
