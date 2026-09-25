package com.universe.wiki.entry.admin.dto;

import com.universe.wiki.domain.credit.CreditStatus;

import java.time.Instant;

/**
 * Presentation DTO cho thông tin ghi nhận công trạng trong giao diện quản trị đóng góp Wiki.
 */
public record AdminWikiContributionCreditDTO(
        CreditStatus status,
        AdminWikiContributionContributorDTO creditedBy,
        Instant creditedAt,
        String creditNote,
        AdminWikiContributionContributorDTO revokedBy,
        Instant revokedAt,
        String revocationReason
) {
    public boolean isActive() {
        return status == CreditStatus.ACTIVE;
    }

    public boolean isRevoked() {
        return status == CreditStatus.REVOKED;
    }
}
