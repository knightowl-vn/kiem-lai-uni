package com.universe.wiki.application.contribution.query;

import com.universe.wiki.domain.credit.CreditStatus;
import com.universe.wiki.domain.credit.WikiContributionCredit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-model đại diện cho thông tin ghi nhận công trạng gắn với đóng góp Wiki
 * dành cho tầng quản trị (Admin Detail View).
 *
 * Chứa các thông tin cần thiết phục vụ trình bày phía quản trị:
 * - Trạng thái công trạng (ACTIVE, REVOKED);
 * - Định danh người ghi nhận & thời điểm ghi nhận & ghi chú;
 * - Định danh người thu hồi & thời điểm thu hồi & lý do thu hồi (khi đã thu hồi).
 */
public record WikiContributionAdminCredit(
        CreditStatus status,
        UUID creditedByUserId,
        Instant creditedAt,
        String creditNote,
        UUID revokedByUserId,
        Instant revokedAt,
        String revocationReason
) {
    public WikiContributionAdminCredit {
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(creditedByUserId, "creditedByUserId cannot be null");
        Objects.requireNonNull(creditedAt, "creditedAt cannot be null");
    }

    public static WikiContributionAdminCredit from(WikiContributionCredit credit) {
        if (credit == null) {
            return null;
        }
        return new WikiContributionAdminCredit(
                credit.getStatus(),
                credit.getCreditedByUserId(),
                credit.getCreditedAt(),
                credit.getCreditNote(),
                credit.getRevokedByUserId(),
                credit.getRevokedAt(),
                credit.getRevocationReason()
        );
    }
}
