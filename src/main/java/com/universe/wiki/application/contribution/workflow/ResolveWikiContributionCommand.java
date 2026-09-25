package com.universe.wiki.application.contribution.workflow;

import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;

import java.util.Objects;
import java.util.UUID;

/**
 * Command yêu cầu giải quyết đóng góp (RESOLVED).
 */
public record ResolveWikiContributionCommand(
        UUID contributionId,
        UUID actorId,
        long expectedVersion,
        WikiContributionResolutionOutcome outcome,
        String resolutionNote
) {
    public ResolveWikiContributionCommand {
        Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        Objects.requireNonNull(actorId, "ID quản trị viên không được để trống.");
        Objects.requireNonNull(outcome, "Kết quả giải quyết không được để trống.");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("Expected version không được nhỏ hơn 0.");
        }
        if (resolutionNote == null || resolutionNote.trim().isEmpty()) {
            throw new IllegalArgumentException("Ghi chú giải quyết không được để trống.");
        }
    }

    public ResolveWikiContributionCommand(
            UUID contributionId,
            UUID actorId,
            long expectedVersion,
            String resolutionNote
    ) {
        this(contributionId, actorId, expectedVersion, WikiContributionResolutionOutcome.APPLIED, resolutionNote);
    }
}
