package com.universe.wiki.domain.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WikiContributionCredit Domain Unit Tests")
class WikiContributionCreditTest {

    private final UUID creditId = UUID.randomUUID();
    private final UUID contributionId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID superAdminId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-25T12:00:00Z");

    @Nested
    @DisplayName("Khởi tạo công trạng (createActive)")
    class CreateActiveTests {

        @Test
        @DisplayName("Tạo thành công công trạng ACTIVE với đầy đủ dữ liệu hợp lệ")
        void shouldCreateActiveCreditSuccessfully() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    "Ghi nhận đóng góp xuất sắc bổ sung bối cảnh nhân vật."
            );

            assertThat(credit.getId()).isEqualTo(creditId);
            assertThat(credit.getContributionId()).isEqualTo(contributionId);
            assertThat(credit.getStatus()).isEqualTo(CreditStatus.ACTIVE);
            assertThat(credit.isActive()).isTrue();
            assertThat(credit.isRevoked()).isFalse();
            assertThat(credit.getCreditedByUserId()).isEqualTo(adminId);
            assertThat(credit.getCreditedAt()).isEqualTo(now);
            assertThat(credit.getCreditNote()).isEqualTo("Ghi nhận đóng góp xuất sắc bổ sung bối cảnh nhân vật.");
            assertThat(credit.getRevokedByUserId()).isNull();
            assertThat(credit.getRevokedAt()).isNull();
            assertThat(credit.getRevocationReason()).isNull();
        }

        @Test
        @DisplayName("Tạo thành công công trạng ACTIVE khi creditNote là null hoặc rỗng")
        void shouldAllowNullOrEmptyCreditNote() {
            WikiContributionCredit creditNullNote = WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    null
            );
            assertThat(creditNullNote.getCreditNote()).isNull();

            WikiContributionCredit creditBlankNote = WikiContributionCredit.createActive(
                    UUID.randomUUID(),
                    contributionId,
                    adminId,
                    now,
                    "   "
            );
            assertThat(creditBlankNote.getCreditNote()).isNull();
        }

        @Test
        @DisplayName("Ném lỗi khi creditNote vượt quá 1000 ký tự")
        void shouldThrowWhenCreditNoteExceedsMaxLength() {
            String longNote = "A".repeat(1001);
            assertThatThrownBy(() -> WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    longNote
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được vượt quá 1000 ký tự");
        }

        @Test
        @DisplayName("Ném lỗi khi thiếu các trường bắt buộc")
        void shouldThrowWhenRequiredFieldsMissing() {
            assertThatThrownBy(() -> WikiContributionCredit.createActive(null, contributionId, adminId, now, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ID ghi nhận công trạng không được để trống");

            assertThatThrownBy(() -> WikiContributionCredit.createActive(creditId, null, adminId, now, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ID đóng góp không được để trống");

            assertThatThrownBy(() -> WikiContributionCredit.createActive(creditId, contributionId, null, now, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ID người ghi nhận không được để trống");

            assertThatThrownBy(() -> WikiContributionCredit.createActive(creditId, contributionId, adminId, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Thời điểm ghi nhận không được để trống");
        }
    }

    @Nested
    @DisplayName("Thu hồi công trạng (revoke)")
    class RevokeTests {

        @Test
        @DisplayName("Thu hồi thành công công trạng ACTIVE")
        void shouldRevokeActiveCreditSuccessfully() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    "Ghi nhận ban đầu."
            );

            Instant revokeTime = now.plus(1, ChronoUnit.HOURS);
            credit.revoke(superAdminId, "Phát hiện nội dung có dấu hiệu sao chép nguồn khác.", revokeTime);

            assertThat(credit.getStatus()).isEqualTo(CreditStatus.REVOKED);
            assertThat(credit.isActive()).isFalse();
            assertThat(credit.isRevoked()).isTrue();
            assertThat(credit.getRevokedByUserId()).isEqualTo(superAdminId);
            assertThat(credit.getRevokedAt()).isEqualTo(revokeTime);
            assertThat(credit.getRevocationReason()).isEqualTo("Phát hiện nội dung có dấu hiệu sao chép nguồn khác.");
        }

        @Test
        @DisplayName("Ném lỗi khi thu hồi lần thứ hai (công trạng đã ở trạng thái REVOKED)")
        void shouldThrowWhenRevokingAlreadyRevokedCredit() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    "Ghi nhận ban đầu."
            );

            Instant revokeTime = now.plus(1, ChronoUnit.HOURS);
            credit.revoke(superAdminId, "Lý do thu hồi hợp lệ.", revokeTime);

            assertThatThrownBy(() -> credit.revoke(superAdminId, "Lý do thu hồi lần hai.", revokeTime.plus(1, ChronoUnit.HOURS)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Chỉ có thể thu hồi công trạng đang ở trạng thái ACTIVE");
        }

        @Test
        @DisplayName("Ném lỗi khi lý do thu hồi null, rỗng hoặc quá ngắn (< 5 ký tự)")
        void shouldThrowWhenRevocationReasonInvalid() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    "Ghi nhận ban đầu."
            );

            assertThatThrownBy(() -> credit.revoke(superAdminId, null, now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Lý do thu hồi không được để trống");

            assertThatThrownBy(() -> credit.revoke(superAdminId, "   ", now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lý do thu hồi phải từ 5 đến 1000 ký tự");

            assertThatThrownBy(() -> credit.revoke(superAdminId, "1234", now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lý do thu hồi phải từ 5 đến 1000 ký tự");
        }

        @Test
        @DisplayName("Ném lỗi khi lý do thu hồi vượt quá 1000 ký tự")
        void shouldThrowWhenRevocationReasonExceedsMaxLength() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    "Ghi nhận ban đầu."
            );

            String longReason = "B".repeat(1001);
            assertThatThrownBy(() -> credit.revoke(superAdminId, longReason, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lý do thu hồi phải từ 5 đến 1000 ký tự");
        }

        @Test
        @DisplayName("Ném lỗi khi thời điểm thu hồi trước thời điểm ghi nhận")
        void shouldThrowWhenRevokeTimeIsBeforeCreditedTime() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    "Ghi nhận ban đầu."
            );

            Instant earlierTime = now.minus(1, ChronoUnit.MINUTES);
            assertThatThrownBy(() -> credit.revoke(superAdminId, "Lý do thu hồi hợp lệ.", earlierTime))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Thời điểm thu hồi không thể trước thời điểm ghi nhận công trạng");
        }

        @Test
        @DisplayName("Thu hồi hợp lệ cắt tỉa khoảng trắng đầu cuối và lưu chuỗi đã trim")
        void shouldTrimAndStoreRevocationReasonOnRevoke() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId,
                    contributionId,
                    adminId,
                    now,
                    "Ghi nhận ban đầu."
            );

            Instant revokeTime = now.plus(1, ChronoUnit.HOURS);
            credit.revoke(superAdminId, "   Lý do có khoảng trắng cần cắt tỉa.   ", revokeTime);

            assertThat(credit.getRevocationReason()).isEqualTo("Lý do có khoảng trắng cần cắt tỉa.");
        }
    }

    @Nested
    @DisplayName("Khôi phục Aggregate (reconstitute)")
    class ReconstituteTests {

        @Test
        @DisplayName("Ném lỗi khi khôi phục ACTIVE nhưng có trường thu hồi")
        void shouldThrowWhenActiveCreditHasRevocationFields() {
            assertThatThrownBy(() -> WikiContributionCredit.reconstitute(
                    creditId,
                    contributionId,
                    CreditStatus.ACTIVE,
                    adminId,
                    now,
                    null,
                    superAdminId,
                    now,
                    "Lý do thu hồi"
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Công trạng ở trạng thái ACTIVE không được chứa thông tin thu hồi");
        }

        @Test
        @DisplayName("Ném lỗi khi khôi phục REVOKED nhưng thiếu trường thu hồi")
        void shouldThrowWhenRevokedCreditLacksRevocationFields() {
            assertThatThrownBy(() -> WikiContributionCredit.reconstitute(
                    creditId,
                    contributionId,
                    CreditStatus.REVOKED,
                    adminId,
                    now,
                    null,
                    null,
                    now,
                    "Lý do"
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ID người thu hồi không được để trống khi đã thu hồi");
        }

        @Test
        @DisplayName("Ném lỗi khi khôi phục REVOKED với lý do là khoảng trắng/rỗng")
        void shouldThrowWhenReconstitutingRevokedWithBlankReason() {
            assertThatThrownBy(() -> WikiContributionCredit.reconstitute(
                    creditId,
                    contributionId,
                    CreditStatus.REVOKED,
                    adminId,
                    now,
                    null,
                    superAdminId,
                    now.plus(1, ChronoUnit.HOURS),
                    "     "
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lý do thu hồi phải từ 5 đến 1000 ký tự");
        }

        @Test
        @DisplayName("Ném lỗi khi khôi phục REVOKED với lý do ngắn hơn 5 ký tự")
        void shouldThrowWhenReconstitutingRevokedWithShortReason() {
            assertThatThrownBy(() -> WikiContributionCredit.reconstitute(
                    creditId,
                    contributionId,
                    CreditStatus.REVOKED,
                    adminId,
                    now,
                    null,
                    superAdminId,
                    now.plus(1, ChronoUnit.HOURS),
                    "Abcd"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lý do thu hồi phải từ 5 đến 1000 ký tự");
        }

        @Test
        @DisplayName("Ném lỗi khi khôi phục REVOKED với lý do vượt quá 1000 ký tự")
        void shouldThrowWhenReconstitutingRevokedWithExcessiveReason() {
            String longReason = "X".repeat(1001);
            assertThatThrownBy(() -> WikiContributionCredit.reconstitute(
                    creditId,
                    contributionId,
                    CreditStatus.REVOKED,
                    adminId,
                    now,
                    null,
                    superAdminId,
                    now.plus(1, ChronoUnit.HOURS),
                    longReason
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lý do thu hồi phải từ 5 đến 1000 ký tự");
        }

        @Test
        @DisplayName("Khôi phục thành công REVOKED với lý do hợp lệ và tự động cắt tỉa khoảng trắng")
        void shouldReconstituteRevokedWithValidReasonAndTrim() {
            Instant revokeTime = now.plus(1, ChronoUnit.HOURS);
            WikiContributionCredit credit = WikiContributionCredit.reconstitute(
                    creditId,
                    contributionId,
                    CreditStatus.REVOKED,
                    adminId,
                    now,
                    "Ghi chú hợp lệ.",
                    superAdminId,
                    revokeTime,
                    "   Lý do thu hồi hợp lệ sau khi trim.   "
            );

            assertThat(credit.getStatus()).isEqualTo(CreditStatus.REVOKED);
            assertThat(credit.getRevokedByUserId()).isEqualTo(superAdminId);
            assertThat(credit.getRevokedAt()).isEqualTo(revokeTime);
            assertThat(credit.getRevocationReason()).isEqualTo("Lý do thu hồi hợp lệ sau khi trim.");
        }
    }
}
