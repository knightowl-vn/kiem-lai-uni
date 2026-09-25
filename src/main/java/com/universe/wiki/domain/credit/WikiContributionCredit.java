package com.universe.wiki.domain.credit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate đại diện cho việc ghi nhận công trạng (Contributor Credit)
 * của người dùng đã đóng góp nội dung bài viết Wiki.
 *
 * Aggregate này hoàn toàn độc lập với vòng đời xét duyệt của WikiContribution
 * nhằm bảo đảm bất biến cốt lõi: RESOLVED != CREDITED.
 */
public class WikiContributionCredit {

    public static final int MAX_CREDIT_NOTE_LENGTH = 1000;
    public static final int MIN_REVOCATION_REASON_LENGTH = 5;
    public static final int MAX_REVOCATION_REASON_LENGTH = 1000;

    private final UUID id;
    private final UUID contributionId;
    private CreditStatus status;
    private final UUID creditedByUserId;
    private final Instant creditedAt;
    private final String creditNote;

    private UUID revokedByUserId;
    private Instant revokedAt;
    private String revocationReason;

    private WikiContributionCredit(
            UUID id,
            UUID contributionId,
            CreditStatus status,
            UUID creditedByUserId,
            Instant creditedAt,
            String creditNote,
            UUID revokedByUserId,
            Instant revokedAt,
            String revocationReason
    ) {
        this.id = Objects.requireNonNull(id, "ID ghi nhận công trạng không được để trống.");
        this.contributionId = Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        this.status = Objects.requireNonNull(status, "Trạng thái công trạng không được để trống.");
        this.creditedByUserId = Objects.requireNonNull(creditedByUserId, "ID người ghi nhận không được để trống.");
        this.creditedAt = Objects.requireNonNull(creditedAt, "Thời điểm ghi nhận không được để trống.");
        this.creditNote = validateCreditNote(creditNote);

        if (status == CreditStatus.ACTIVE) {
            if (revokedByUserId != null || revokedAt != null || revocationReason != null) {
                throw new IllegalStateException("Công trạng ở trạng thái ACTIVE không được chứa thông tin thu hồi.");
            }
            this.revokedByUserId = null;
            this.revokedAt = null;
            this.revocationReason = null;
        } else if (status == CreditStatus.REVOKED) {
            this.revokedByUserId = Objects.requireNonNull(revokedByUserId, "ID người thu hồi không được để trống khi đã thu hồi.");
            this.revokedAt = Objects.requireNonNull(revokedAt, "Thời điểm thu hồi không được để trống khi đã thu hồi.");
            if (revokedAt.isBefore(creditedAt)) {
                throw new IllegalArgumentException("Thời điểm thu hồi không thể trước thời điểm ghi nhận công trạng.");
            }
            this.revocationReason = validateRevocationReason(revocationReason);
        } else {
            this.revokedByUserId = null;
            this.revokedAt = null;
            this.revocationReason = null;
        }
    }

    /**
     * Tạo mới một bản ghi ghi nhận công trạng có hiệu lực (ACTIVE).
     */
    public static WikiContributionCredit createActive(
            UUID id,
            UUID contributionId,
            UUID creditedByUserId,
            Instant creditedAt,
            String creditNote
    ) {
        return new WikiContributionCredit(
                id,
                contributionId,
                CreditStatus.ACTIVE,
                creditedByUserId,
                creditedAt,
                creditNote,
                null,
                null,
                null
        );
    }

    /**
     * Khôi phục Aggregate từ tầng persistence (Rehydrate/Reconstitute).
     */
    public static WikiContributionCredit reconstitute(
            UUID id,
            UUID contributionId,
            CreditStatus status,
            UUID creditedByUserId,
            Instant creditedAt,
            String creditNote,
            UUID revokedByUserId,
            Instant revokedAt,
            String revocationReason
    ) {
        return new WikiContributionCredit(
                id,
                contributionId,
                status,
                creditedByUserId,
                creditedAt,
                creditNote,
                revokedByUserId,
                revokedAt,
                revocationReason
        );
    }

    /**
     * Thu hồi công trạng (chuyển trạng thái từ ACTIVE sang REVOKED).
     * Thao tác này là bất khả nghịch (terminal) trong MS-05H8.
     *
     * @param revokerUserId ID quản trị viên cấp cao thực hiện thu hồi
     * @param reason lý do thu hồi (từ 5 đến 1000 ký tự)
     * @param now thời điểm thực hiện
     */
    public void revoke(UUID revokerUserId, String reason, Instant now) {
        if (this.status != CreditStatus.ACTIVE) {
            throw new IllegalStateException(
                    String.format("Chỉ có thể thu hồi công trạng đang ở trạng thái ACTIVE; trạng thái hiện tại: %s.", this.status)
            );
        }
        Objects.requireNonNull(revokerUserId, "ID người thu hồi không được để trống.");
        Objects.requireNonNull(now, "Thời điểm thu hồi không được để trống.");

        if (now.isBefore(this.creditedAt)) {
            throw new IllegalArgumentException("Thời điểm thu hồi không thể trước thời điểm ghi nhận công trạng.");
        }

        String trimmedReason = validateRevocationReason(reason);

        this.status = CreditStatus.REVOKED;
        this.revokedByUserId = revokerUserId;
        this.revokedAt = now;
        this.revocationReason = trimmedReason;
    }

    private static String validateRevocationReason(String reason) {
        Objects.requireNonNull(reason, "Lý do thu hồi không được để trống.");
        String trimmed = reason.trim();
        if (trimmed.length() < MIN_REVOCATION_REASON_LENGTH || trimmed.length() > MAX_REVOCATION_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Lý do thu hồi phải từ %d đến %d ký tự.", MIN_REVOCATION_REASON_LENGTH, MAX_REVOCATION_REASON_LENGTH)
            );
        }
        return trimmed;
    }

    private static String validateCreditNote(String creditNote) {
        if (creditNote == null) {
            return null;
        }
        String trimmed = creditNote.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_CREDIT_NOTE_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Ghi chú ghi nhận công trạng không được vượt quá %d ký tự.", MAX_CREDIT_NOTE_LENGTH)
            );
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getContributionId() {
        return contributionId;
    }

    public CreditStatus getStatus() {
        return status;
    }

    public UUID getCreditedByUserId() {
        return creditedByUserId;
    }

    public Instant getCreditedAt() {
        return creditedAt;
    }

    public String getCreditNote() {
        return creditNote;
    }

    public UUID getRevokedByUserId() {
        return revokedByUserId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public boolean isActive() {
        return this.status == CreditStatus.ACTIVE;
    }

    public boolean isRevoked() {
        return this.status == CreditStatus.REVOKED;
    }
}
