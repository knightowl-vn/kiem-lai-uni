package com.universe.wiki.application.contribution.credit;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiContributionCreditNotFoundException;
import com.universe.wiki.application.ports.WikiContributionCreditRepositoryPort;
import com.universe.wiki.domain.credit.CreditStatus;
import com.universe.wiki.domain.credit.WikiContributionCredit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RevokeWikiContributionCreditUseCase Unit Tests")
class RevokeWikiContributionCreditUseCaseTest {

    @Mock
    private WikiContributionCreditRepositoryPort creditRepository;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private ClockPort clockPort;

    private RevokeWikiContributionCreditUseCase useCase;

    private final UUID contributionId = UUID.randomUUID();
    private final UUID creditId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID superAdminId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-25T15:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new RevokeWikiContributionCreditUseCase(
                creditRepository,
                userIdentityContract,
                clockPort
        );
    }

    private UserDTO createActiveUser(UUID userId, String role) {
        return new UserDTO(
                userId,
                role.toLowerCase() + "@universe.local",
                "User " + role,
                null,
                "ACTIVE",
                role,
                now.minusSeconds(86400)
        );
    }

    @Nested
    @DisplayName("Thu hồi công trạng thành công")
    class SuccessScenarios {

        @Test
        @DisplayName("SUPER_ADMIN thu hồi thành công công trạng ACTIVE")
        void shouldRevokeCreditSuccessfullyBySuperAdmin() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId, contributionId, adminId, now.minusSeconds(3600), "Ghi nhận ban đầu."
            );
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.of(credit));
            when(userIdentityContract.findById(superAdminId)).thenReturn(Optional.of(createActiveUser(superAdminId, "SUPER_ADMIN")));
            when(clockPort.now()).thenReturn(now);
            when(creditRepository.save(any(WikiContributionCredit.class))).thenAnswer(inv -> inv.getArgument(0));

            RevokeWikiContributionCreditCommand command = new RevokeWikiContributionCreditCommand(
                    contributionId,
                    superAdminId,
                    "Phát hiện nội dung có vi phạm bản quyền từ nguồn khác."
            );

            WikiContributionCredit result = useCase.execute(command);

            assertThat(result.getStatus()).isEqualTo(CreditStatus.REVOKED);
            assertThat(result.isRevoked()).isTrue();
            assertThat(result.getRevokedByUserId()).isEqualTo(superAdminId);
            assertThat(result.getRevocationReason()).isEqualTo("Phát hiện nội dung có vi phạm bản quyền từ nguồn khác.");
            assertThat(result.getRevokedAt()).isEqualTo(now);

            verify(creditRepository).save(credit);
        }
    }

    @Nested
    @DisplayName("Thất bại do phân quyền")
    class AuthorizationTests {

        @Test
        @DisplayName("ADMIN thông thường bị từ chối khi thực hiện thu hồi")
        void shouldRejectRegularAdmin() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId, contributionId, adminId, now.minusSeconds(3600), "Ghi nhận ban đầu."
            );
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.of(credit));
            when(userIdentityContract.findById(adminId)).thenReturn(Optional.of(createActiveUser(adminId, "ADMIN")));

            RevokeWikiContributionCreditCommand command = new RevokeWikiContributionCreditCommand(
                    contributionId,
                    adminId,
                    "Lý do thu hồi hợp lệ dài hơn 5 ký tự."
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Chỉ Quản trị viên cấp cao (SUPER_ADMIN) mới có quyền thu hồi");
        }
    }

    @Nested
    @DisplayName("Thất bại do trạng thái công trạng hoặc lý do thu hồi")
    class ValidationTests {

        @Test
        @DisplayName("Ném lỗi khi không tìm thấy công trạng theo contributionId")
        void shouldThrowWhenCreditNotFound() {
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.empty());

            RevokeWikiContributionCreditCommand command = new RevokeWikiContributionCreditCommand(
                    contributionId,
                    superAdminId,
                    "Lý do thu hồi hợp lệ."
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(WikiContributionCreditNotFoundException.class);
        }

        @Test
        @DisplayName("Ném lỗi khi công trạng đã bị thu hồi trước đó")
        void shouldThrowWhenCreditAlreadyRevoked() {
            WikiContributionCredit credit = WikiContributionCredit.reconstitute(
                    creditId,
                    contributionId,
                    CreditStatus.REVOKED,
                    adminId,
                    now.minusSeconds(7200),
                    "Note",
                    superAdminId,
                    now.minusSeconds(3600),
                    "Đã thu hồi trước đó."
            );
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.of(credit));

            RevokeWikiContributionCreditCommand command = new RevokeWikiContributionCreditCommand(
                    contributionId,
                    superAdminId,
                    "Lý do thu hồi mới."
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("công trạng hiện tại đã bị thu hồi");
        }

        @Test
        @DisplayName("Ném lỗi khi lý do thu hồi để trống hoặc quá ngắn (< 5 ký tự)")
        void shouldThrowWhenRevocationReasonInvalid() {
            WikiContributionCredit credit = WikiContributionCredit.createActive(
                    creditId, contributionId, adminId, now.minusSeconds(3600), "Ghi nhận ban đầu."
            );
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.of(credit));
            when(userIdentityContract.findById(superAdminId)).thenReturn(Optional.of(createActiveUser(superAdminId, "SUPER_ADMIN")));

            RevokeWikiContributionCreditCommand blankCmd = new RevokeWikiContributionCreditCommand(
                    contributionId,
                    superAdminId,
                    "   "
            );
            assertThatThrownBy(() -> useCase.execute(blankCmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lý do thu hồi phải từ 5 đến 1000 ký tự");

            RevokeWikiContributionCreditCommand shortCmd = new RevokeWikiContributionCreditCommand(
                    contributionId,
                    superAdminId,
                    "ABCD"
            );
            assertThatThrownBy(() -> useCase.execute(shortCmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lý do thu hồi phải từ 5 đến 1000 ký tự");
        }
    }
}
