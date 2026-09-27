package com.universe.wiki.application.contribution.credit;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiContributionAlreadyCreditedException;
import com.universe.wiki.application.ports.WikiContributionCreditRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.credit.CreditStatus;
import com.universe.wiki.domain.credit.WikiContributionCredit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrantWikiContributionCreditUseCase Unit Tests")
class GrantWikiContributionCreditUseCaseTest {

    @Mock
    private WikiContributionRepositoryPort contributionRepository;

    @Mock
    private WikiContributionCreditRepositoryPort creditRepository;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private GrantWikiContributionCreditUseCase useCase;

    private final UUID contributionId = UUID.randomUUID();
    private final UUID articleId = UUID.randomUUID();
    private final UUID contributorUserId = UUID.randomUUID();
    private final UUID resolvingAdminId = UUID.randomUUID();
    private final UUID superAdminId = UUID.randomUUID();
    private final UUID unrelatedAdminId = UUID.randomUUID();
    private final UUID generatedCreditId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-25T14:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GrantWikiContributionCreditUseCase(
                contributionRepository,
                creditRepository,
                userIdentityContract,
                idGeneratorPort,
                clockPort
        );
    }

    private WikiContribution createResolvedContribution(WikiContributionResolutionOutcome outcome) {
        Long resolvedVersion = (outcome == WikiContributionResolutionOutcome.APPLIED) ? 3L : 2L;
        return WikiContribution.rehydrate(
                contributionId,
                articleId,
                "CHARACTER",
                "Tiêu Viêm",
                "tieu-viem",
                2L,
                contributorUserId,
                WikiContributionContextType.GENERAL,
                WikiContributionType.INCORRECT_INFORMATION,
                "Bổ sung công pháp Phần Quyết.",
                null, null, null, null,
                WikiContributionStatus.RESOLVED,
                1L,
                now.minusSeconds(7200),
                now.minusSeconds(3600),
                "Đã xử lý xong",
                resolvingAdminId,
                now.minusSeconds(3600),
                resolvedVersion,
                resolvingAdminId,
                now.minusSeconds(5000),
                resolvingAdminId,
                now.minusSeconds(5000),
                2L,
                outcome
        );
    }

    private WikiContribution createNewContribution() {
        return WikiContribution.createGeneral(
                contributionId,
                articleId,
                "CHARACTER",
                "Tiêu Viêm",
                "tieu-viem",
                2L,
                contributorUserId,
                WikiContributionType.INCORRECT_INFORMATION,
                "Bổ sung công pháp Phần Quyết.",
                now
        );
    }

    private WikiContribution createReviewingContribution() {
        WikiContribution contribution = WikiContribution.createGeneral(
                contributionId,
                articleId,
                "CHARACTER",
                "Tiêu Viêm",
                "tieu-viem",
                2L,
                contributorUserId,
                WikiContributionType.INCORRECT_INFORMATION,
                "Bổ sung công pháp Phần Quyết.",
                now.minusSeconds(3600)
        );
        contribution.startReview(resolvingAdminId, 2L, now);
        return contribution;
    }

    private WikiContribution createRejectedContribution() {
        WikiContribution contribution = WikiContribution.createGeneral(
                contributionId,
                articleId,
                "CHARACTER",
                "Tiêu Viêm",
                "tieu-viem",
                2L,
                contributorUserId,
                WikiContributionType.INCORRECT_INFORMATION,
                "Bổ sung công pháp Phần Quyết.",
                now.minusSeconds(3600)
        );
        contribution.startReview(resolvingAdminId, 2L, now.minusSeconds(1800));
        contribution.reject(resolvingAdminId, "Thông tin chưa có nguồn xác thực hợp lệ.", now);
        return contribution;
    }

    private UserDTO createActiveUser(UUID userId, String role) {
        return new UserDTO(
                userId,
                role.toLowerCase() + "@universe.local",
                "Admin User",
                null,
                "ACTIVE",
                role,
                now.minusSeconds(86400)
        );
    }

    @Nested
    @DisplayName("Ghi nhận công trạng thành công")
    class SuccessScenarios {

        @Test
        @DisplayName("Ghi nhận thành công khi đóng góp RESOLVED + APPLIED bởi người giải quyết (resolving admin)")
        void shouldGrantCreditForResolvedAppliedByResolvingAdmin() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.APPLIED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(resolvingAdminId)).thenReturn(Optional.of(createActiveUser(resolvingAdminId, "ADMIN")));
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.empty());
            when(clockPort.now()).thenReturn(now);
            when(idGeneratorPort.generate()).thenReturn(generatedCreditId);
            when(creditRepository.save(any(WikiContributionCredit.class))).thenAnswer(inv -> inv.getArgument(0));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    resolvingAdminId,
                    "Cảm ơn bạn đã đóng góp nội dung chuẩn xác."
            );

            WikiContributionCredit result = useCase.execute(command);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(generatedCreditId);
            assertThat(result.getContributionId()).isEqualTo(contributionId);
            assertThat(result.getStatus()).isEqualTo(CreditStatus.ACTIVE);
            assertThat(result.getCreditedByUserId()).isEqualTo(resolvingAdminId);
            assertThat(result.getCreditNote()).isEqualTo("Cảm ơn bạn đã đóng góp nội dung chuẩn xác.");

            ArgumentCaptor<WikiContributionCredit> captor = ArgumentCaptor.forClass(WikiContributionCredit.class);
            verify(creditRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(CreditStatus.ACTIVE);
        }

        @Test
        @DisplayName("Ghi nhận thành công khi đóng góp RESOLVED + APPLIED và creditNote là null")
        void shouldGrantCreditForAppliedWithoutNote() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.APPLIED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(resolvingAdminId)).thenReturn(Optional.of(createActiveUser(resolvingAdminId, "ADMIN")));
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.empty());
            when(clockPort.now()).thenReturn(now);
            when(idGeneratorPort.generate()).thenReturn(generatedCreditId);
            when(creditRepository.save(any(WikiContributionCredit.class))).thenAnswer(inv -> inv.getArgument(0));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    resolvingAdminId,
                    null
            );

            WikiContributionCredit result = useCase.execute(command);

            assertThat(result.getCreditNote()).isNull();
        }

        @Test
        @DisplayName("Ghi nhận thành công khi đóng góp RESOLVED + NO_CHANGE_NEEDED kèm ghi chú hợp lệ")
        void shouldGrantCreditForNoChangeNeededWithValidNote() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.NO_CHANGE_NEEDED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(resolvingAdminId)).thenReturn(Optional.of(createActiveUser(resolvingAdminId, "ADMIN")));
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.empty());
            when(clockPort.now()).thenReturn(now);
            when(idGeneratorPort.generate()).thenReturn(generatedCreditId);
            when(creditRepository.save(any(WikiContributionCredit.class))).thenAnswer(inv -> inv.getArgument(0));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    resolvingAdminId,
                    "Thông tin đối chiếu rất kỹ lưỡng và hữu ích."
            );

            WikiContributionCredit result = useCase.execute(command);

            assertThat(result.getStatus()).isEqualTo(CreditStatus.ACTIVE);
            assertThat(result.getCreditNote()).isEqualTo("Thông tin đối chiếu rất kỹ lưỡng và hữu ích.");
        }

        @Test
        @DisplayName("SUPER_ADMIN được phép ghi nhận công trạng cho đóng góp do người khác giải quyết")
        void shouldAllowSuperAdminToGrantCredit() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.APPLIED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(superAdminId)).thenReturn(Optional.of(createActiveUser(superAdminId, "SUPER_ADMIN")));
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.empty());
            when(clockPort.now()).thenReturn(now);
            when(idGeneratorPort.generate()).thenReturn(generatedCreditId);
            when(creditRepository.save(any(WikiContributionCredit.class))).thenAnswer(inv -> inv.getArgument(0));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    superAdminId,
                    "Super admin phê duyệt ghi nhận."
            );

            WikiContributionCredit result = useCase.execute(command);

            assertThat(result.getCreditedByUserId()).isEqualTo(superAdminId);
        }
    }

    @Nested
    @DisplayName("Thất bại do trạng thái hoặc kết quả xử lý không hợp lệ")
    class StatusAndOutcomeValidationTests {

        @Test
        @DisplayName("Ném lỗi khi đóng góp ở trạng thái NEW")
        void shouldThrowWhenContributionIsNew() {
            WikiContribution contribution = createNewContribution();
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(contributionId, resolvingAdminId, "Note");

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Chỉ có thể ghi nhận công trạng cho đóng góp đã được giải quyết (RESOLVED)");
        }

        @Test
        @DisplayName("Ném lỗi khi đóng góp ở trạng thái REVIEWING")
        void shouldThrowWhenContributionIsReviewing() {
            WikiContribution contribution = createReviewingContribution();
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(contributionId, resolvingAdminId, "Note");

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Chỉ có thể ghi nhận công trạng cho đóng góp đã được giải quyết (RESOLVED)");
        }

        @Test
        @DisplayName("Ném lỗi khi đóng góp ở trạng thái REJECTED")
        void shouldThrowWhenContributionIsRejected() {
            WikiContribution contribution = createRejectedContribution();
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(contributionId, resolvingAdminId, "Note");

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Chỉ có thể ghi nhận công trạng cho đóng góp đã được giải quyết (RESOLVED)");
        }

        @Test
        @DisplayName("Ném lỗi khi kết quả xử lý là null")
        void shouldThrowWhenResolutionOutcomeIsNull() {
            WikiContribution contribution = createResolvedContribution(null);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(contributionId, resolvingAdminId, "Note");

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outcome == null");
        }

        @Test
        @DisplayName("Ném lỗi khi kết quả xử lý là DUPLICATE")
        void shouldThrowWhenResolutionOutcomeIsDuplicate() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.DUPLICATE);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(contributionId, resolvingAdminId, "Note");

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("không đủ điều kiện ghi nhận công trạng");
        }

        @Test
        @DisplayName("Ném lỗi khi NO_CHANGE_NEEDED nhưng thiếu ghi chú hoặc ghi chú không hợp lệ (< 5 ký tự)")
        void shouldThrowWhenNoChangeNeededWithoutValidNote() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.NO_CHANGE_NEEDED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(resolvingAdminId)).thenReturn(Optional.of(createActiveUser(resolvingAdminId, "ADMIN")));

            // Case 1: note null
            GrantWikiContributionCreditCommand cmdNullNote = new GrantWikiContributionCreditCommand(contributionId, resolvingAdminId, null);
            assertThatThrownBy(() -> useCase.execute(cmdNullNote))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ghi chú ghi nhận công trạng là bắt buộc");

            // Case 2: note blank
            GrantWikiContributionCreditCommand cmdBlankNote = new GrantWikiContributionCreditCommand(contributionId, resolvingAdminId, "   ");
            assertThatThrownBy(() -> useCase.execute(cmdBlankNote))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ghi chú ghi nhận công trạng là bắt buộc");

            // Case 3: note < 5 chars
            GrantWikiContributionCreditCommand cmdShortNote = new GrantWikiContributionCreditCommand(contributionId, resolvingAdminId, "Abcd");
            assertThatThrownBy(() -> useCase.execute(cmdShortNote))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Độ dài ghi chú ghi nhận công trạng phải từ 5 đến 1000 ký tự");
        }
    }

    @Nested
    @DisplayName("Thất bại do phân quyền quản trị viên")
    class AuthorizationValidationTests {

        @Test
        @DisplayName("Quản trị viên không liên quan (unrelated ADMIN) bị từ chối")
        void shouldRejectUnrelatedAdmin() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.APPLIED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(unrelatedAdminId)).thenReturn(Optional.of(createActiveUser(unrelatedAdminId, "ADMIN")));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    unrelatedAdminId,
                    "Note"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Chỉ người đã giải quyết đóng góp hoặc Quản trị viên cấp cao (SUPER_ADMIN) mới có quyền");
        }

        @Test
        @DisplayName("Người dùng đã bị hạ quyền xuống USER (dù là người giải quyết ban đầu) bị từ chối")
        void shouldRejectDemotedUserEvenIfOriginalResolver() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.APPLIED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(resolvingAdminId)).thenReturn(Optional.of(createActiveUser(resolvingAdminId, "USER")));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    resolvingAdminId,
                    "Note"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Người thực hiện phải có vai trò Quản trị viên (ADMIN hoặc SUPER_ADMIN)");
        }

        @Test
        @DisplayName("Ném lỗi khi tài khoản SUPER_ADMIN không còn ACTIVE")
        void shouldThrowWhenSuperAdminAccountIsNotActive() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.APPLIED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            UserDTO inactiveSuperAdmin = new UserDTO(
                    superAdminId, "super@universe.local", "SuperAdmin", null, "SUSPENDED", "SUPER_ADMIN", now
            );
            when(userIdentityContract.findById(superAdminId)).thenReturn(Optional.of(inactiveSuperAdmin));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    superAdminId,
                    "Note"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Tài khoản người thực hiện không còn hoạt động");
        }
    }

    @Nested
    @DisplayName("Thất bại do công trạng đã tồn tại hoặc đã bị thu hồi")
    class ExistenceAndStateValidationTests {

        @Test
        @DisplayName("Ném lỗi khi đóng góp đã có công trạng ACTIVE")
        void shouldThrowWhenCreditAlreadyActive() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.APPLIED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(resolvingAdminId)).thenReturn(Optional.of(createActiveUser(resolvingAdminId, "ADMIN")));

            WikiContributionCredit existingActive = WikiContributionCredit.createActive(
                    UUID.randomUUID(), contributionId, resolvingAdminId, now.minusSeconds(100), "Existing"
            );
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.of(existingActive));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    resolvingAdminId,
                    "Note"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(WikiContributionAlreadyCreditedException.class);

            verify(creditRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ném lỗi khi đóng góp đã từng có công trạng nhưng đã bị thu hồi (REVOKED)")
        void shouldThrowWhenCreditWasRevoked() {
            WikiContribution contribution = createResolvedContribution(WikiContributionResolutionOutcome.APPLIED);
            when(contributionRepository.findById(contributionId)).thenReturn(Optional.of(contribution));
            when(userIdentityContract.findById(resolvingAdminId)).thenReturn(Optional.of(createActiveUser(resolvingAdminId, "ADMIN")));

            WikiContributionCredit existingRevoked = WikiContributionCredit.reconstitute(
                    UUID.randomUUID(),
                    contributionId,
                    CreditStatus.REVOKED,
                    resolvingAdminId,
                    now.minusSeconds(200),
                    "Note",
                    superAdminId,
                    now.minusSeconds(50),
                    "Revoked reason"
            );
            when(creditRepository.findByContributionId(contributionId)).thenReturn(Optional.of(existingRevoked));

            GrantWikiContributionCreditCommand command = new GrantWikiContributionCreditCommand(
                    contributionId,
                    resolvingAdminId,
                    "Note"
            );

            assertThatThrownBy(() -> useCase.execute(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("không thể tái ghi nhận");

            verify(creditRepository, never()).save(any());
        }
    }
}
