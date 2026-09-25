package com.universe.wiki.application.contribution.credit;

import java.util.Objects;
import java.util.UUID;

/**
 * Command yêu cầu ghi nhận công trạng cho đóng góp Wiki đã được chấp thuận.
 */
public record GrantWikiContributionCreditCommand(
        UUID contributionId,
        UUID actorId,
        String creditNote
) {
    public GrantWikiContributionCreditCommand {
        Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        Objects.requireNonNull(actorId, "ID quản trị viên không được để trống.");
    }

    public GrantWikiContributionCreditCommand(UUID contributionId, UUID actorId) {
        this(contributionId, actorId, null);
    }
}
