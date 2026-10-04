package com.universe.community.entry.admin.dto;

import com.universe.community.domain.CommunityPostStatus;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Detailed composite data for Admin Community Post Report detail view.
 */
public record AdminCommunityPostReportDetailDTO(
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
        Instant postCreatedAt,
        Instant postUpdatedAt,
        int postContentVersion,
        ReportModerationAction moderationAction,
        AdminCommunityPostUserDTO resolver,
        Instant resolvedAt,
        List<AdminCommunityPostModerationEventDTO> moderationHistory
) {
    public AdminCommunityPostReportDetailDTO {
        Objects.requireNonNull(reportId, "reportId cannot be null.");
        Objects.requireNonNull(reporter, "reporter cannot be null.");
        Objects.requireNonNull(reason, "reason cannot be null.");
        Objects.requireNonNull(reportedCaptionSnapshot, "reportedCaptionSnapshot cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(targetPostId, "targetPostId cannot be null.");
        moderationHistory = moderationHistory != null ? List.copyOf(moderationHistory) : List.of();
    }
}
