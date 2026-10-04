package com.universe.community.entry.admin.dto;

import com.universe.community.domain.CommunityPostStatus;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Enriched row item for the Admin Community Post Report Queue.
 */
public record AdminCommunityPostReportItemDTO(
        UUID reportId,
        AdminCommunityPostUserDTO reporter,
        ReportReason reason,
        String description,
        String reportedCaptionSnapshot,
        UUID evidenceMediaAssetId,
        Instant createdAt,
        ReportStatus status,
        UUID targetPostId,
        boolean postExists,
        CommunityPostStatus postStatus,
        String currentCaption,
        UUID imageMediaAssetId,
        AdminCommunityPostUserDTO postAuthor,
        ReportModerationAction moderationAction,
        AdminCommunityPostUserDTO resolver,
        Instant resolvedAt
) {
    public AdminCommunityPostReportItemDTO {
        Objects.requireNonNull(reportId, "reportId cannot be null.");
        Objects.requireNonNull(reporter, "reporter cannot be null.");
        Objects.requireNonNull(reason, "reason cannot be null.");
        Objects.requireNonNull(reportedCaptionSnapshot, "reportedCaptionSnapshot cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(targetPostId, "targetPostId cannot be null.");
    }
}
