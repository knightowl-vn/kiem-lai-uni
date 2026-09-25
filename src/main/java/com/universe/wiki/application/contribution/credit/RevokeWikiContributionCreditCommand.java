package com.universe.wiki.application.contribution.credit;

import java.util.Objects;
import java.util.UUID;

/**
 * Command yêu cầu thu hồi công trạng người đóng góp (SUPER_ADMIN only).
 */
public record RevokeWikiContributionCreditCommand(
        UUID contributionId,
        UUID actorId,
        String revocationReason
) {
    public RevokeWikiContributionCreditCommand {
        Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        Objects.requireNonNull(actorId, "ID quản trị viên không được để trống.");
        Objects.requireNonNull(revocationReason, "Lý do thu hồi không được để trống.");
    }
}
