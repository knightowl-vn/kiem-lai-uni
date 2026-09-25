package com.universe.wiki.application.contribution.workflow;

import java.util.Objects;
import java.util.UUID;

/**
 * Command yêu cầu từ chối đóng góp (REJECTED).
 */
public record RejectWikiContributionCommand(
        UUID contributionId,
        UUID actorId,
        long expectedVersion,
        String resolutionNote
) {
    public RejectWikiContributionCommand {
        Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        Objects.requireNonNull(actorId, "ID quản trị viên không được để trống.");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("Expected version không được nhỏ hơn 0.");
        }
        if (resolutionNote == null || resolutionNote.trim().isEmpty()) {
            throw new IllegalArgumentException("Ghi chú từ chối không được để trống.");
        }
    }
}
