package com.universe.wiki.entry.admin.dto;

import com.universe.wiki.domain.contribution.WikiContributionEventType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * Presentation DTO cho sự kiện kiểm toán quy trình đóng góp (workflow event).
 */
public record AdminWikiContributionWorkflowEventDTO(
        UUID eventId,
        UUID contributionId,
        WikiContributionEventType eventType,
        AdminWikiContributionContributorDTO actor,
        AdminWikiContributionContributorDTO targetUser,
        Long articleContentVersion,
        WikiContributionResolutionOutcome resolutionOutcome,
        String note,
        Instant createdAt
) {
}
