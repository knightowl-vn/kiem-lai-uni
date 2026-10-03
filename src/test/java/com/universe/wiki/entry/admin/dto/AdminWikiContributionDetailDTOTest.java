package com.universe.wiki.entry.admin.dto;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.credit.CreditStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Admin Wiki Contribution Detail DTO UI Authorization Helper Tests")
class AdminWikiContributionDetailDTOTest {

    private final UUID contributorId = UUID.randomUUID();
    private final UUID resolverId = UUID.randomUUID();
    private final UUID otherAdminId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-25T12:00:00Z");

    private AdminWikiContributionContributorDTO contributor() {
        return new AdminWikiContributionContributorDTO(contributorId, "Contributor", null, true);
    }

    private AdminWikiContributionContributorDTO resolver() {
        return new AdminWikiContributionContributorDTO(resolverId, "Original Resolver Admin", null, true);
    }

    private AdminWikiContributionDetailDTO createDetail(
            WikiContributionStatus status,
            WikiContributionResolutionOutcome resolutionOutcome,
            AdminWikiContributionContributorDTO resolverDTO,
            AdminWikiContributionCreditDTO creditDTO
    ) {
        return new AdminWikiContributionDetailDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                contributor(),
                WikiContributionContextType.TEXT_SELECTION,
                WikiContributionType.WORDING,
                "Lỗi diễn đạt",
                "đoạn văn",
                null,
                null,
                null,
                status,
                1L,
                now.minusSeconds(100),
                now,
                List.of(),
                "Ghi chú",
                resolverDTO,
                now,
                2L,
                null,
                null,
                null,
                null,
                null,
                resolutionOutcome,
                List.of(),
                creditDTO
        );
    }

    private AdminWikiContributionCreditDTO activeCredit() {
        return new AdminWikiContributionCreditDTO(
                CreditStatus.ACTIVE,
                resolver(),
                now,
                "Đóng góp chuẩn xác",
                null,
                null,
                null
        );
    }

    private AdminWikiContributionCreditDTO revokedCredit() {
        return new AdminWikiContributionCreditDTO(
                CreditStatus.REVOKED,
                resolver(),
                now.minusSeconds(3600),
                "Ghi chú ban đầu",
                new AdminWikiContributionContributorDTO(otherAdminId, "Super Admin", null, true),
                now,
                "Lý do thu hồi"
        );
    }

    private AuthenticatedRequestIdentity identity(UUID userId, UserRole role, UserStatus status) {
        return new AuthenticatedRequestIdentity(userId, "user@universe.local", "Test User", null, "test_user", status, role);
    }

    @Nested
    @DisplayName("canGrantCredit UI Authorization Tests")
    class CanGrantCreditTests {

        @Test
        @DisplayName("1. RESOLVED + APPLIED + no credit + ACTIVE ADMIN who is original resolver -> true")
        void shouldAllowGrantWhenActiveAdminIsOriginalResolver() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    null
            );
            AuthenticatedRequestIdentity currentAdmin = identity(resolverId, UserRole.ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canGrantCredit(currentAdmin)).isTrue();
        }

        @Test
        @DisplayName("2. RESOLVED + APPLIED + no credit + ACTIVE ADMIN who is unrelated -> false")
        void shouldDenyGrantWhenActiveAdminIsUnrelated() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    null
            );
            AuthenticatedRequestIdentity currentAdmin = identity(otherAdminId, UserRole.ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canGrantCredit(currentAdmin)).isFalse();
        }

        @Test
        @DisplayName("3. RESOLVED + APPLIED + no credit + ACTIVE SUPER_ADMIN -> true")
        void shouldAllowGrantWhenActiveSuperAdmin() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    null
            );
            AuthenticatedRequestIdentity currentAdmin = identity(otherAdminId, UserRole.SUPER_ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canGrantCredit(currentAdmin)).isTrue();
        }

        @Test
        @DisplayName("4. RESOLVED + APPLIED + no credit + resolver role USER -> false")
        void shouldDenyGrantWhenResolverRoleIsUser() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    null
            );
            AuthenticatedRequestIdentity currentAdmin = identity(resolverId, UserRole.USER, UserStatus.ACTIVE);

            assertThat(dto.canGrantCredit(currentAdmin)).isFalse();
        }

        @Test
        @DisplayName("5. RESOLVED + APPLIED + no credit + inactive resolver/admin -> false")
        void shouldDenyGrantWhenResolverAdminIsInactive() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    null
            );
            AuthenticatedRequestIdentity currentAdmin = identity(resolverId, UserRole.ADMIN, UserStatus.BLOCKED);

            assertThat(dto.canGrantCredit(currentAdmin)).isFalse();
        }

        @Test
        @DisplayName("6. RESOLVED + DUPLICATE outcome -> false")
        void shouldDenyGrantWhenResolutionOutcomeIsDuplicate() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.DUPLICATE,
                    resolver(),
                    null
            );
            AuthenticatedRequestIdentity currentAdmin = identity(resolverId, UserRole.ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canGrantCredit(currentAdmin)).isFalse();
        }

        @Test
        @DisplayName("7. Existing ACTIVE credit -> false")
        void shouldDenyGrantWhenCreditIsAlreadyActive() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    activeCredit()
            );
            AuthenticatedRequestIdentity currentAdmin = identity(resolverId, UserRole.ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canGrantCredit(currentAdmin)).isFalse();
        }

        @Test
        @DisplayName("8. Existing REVOKED credit -> false")
        void shouldDenyGrantWhenCreditIsAlreadyRevoked() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    revokedCredit()
            );
            AuthenticatedRequestIdentity currentAdmin = identity(resolverId, UserRole.ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canGrantCredit(currentAdmin)).isFalse();
        }
    }

    @Nested
    @DisplayName("canRevokeCredit UI Authorization Tests")
    class CanRevokeCreditTests {

        @Test
        @DisplayName("9. ACTIVE credit + ACTIVE SUPER_ADMIN -> true")
        void shouldAllowRevokeWhenActiveCreditAndActiveSuperAdmin() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    activeCredit()
            );
            AuthenticatedRequestIdentity currentAdmin = identity(otherAdminId, UserRole.SUPER_ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canRevokeCredit(currentAdmin)).isTrue();
        }

        @Test
        @DisplayName("10. ACTIVE credit + ADMIN -> false")
        void shouldDenyRevokeWhenActiveCreditAndAdmin() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    activeCredit()
            );
            AuthenticatedRequestIdentity currentAdmin = identity(resolverId, UserRole.ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canRevokeCredit(currentAdmin)).isFalse();
        }

        @Test
        @DisplayName("11. REVOKED credit + SUPER_ADMIN -> false")
        void shouldDenyRevokeWhenCreditIsAlreadyRevoked() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.RESOLVED,
                    WikiContributionResolutionOutcome.APPLIED,
                    resolver(),
                    revokedCredit()
            );
            AuthenticatedRequestIdentity currentAdmin = identity(otherAdminId, UserRole.SUPER_ADMIN, UserStatus.ACTIVE);

            assertThat(dto.canRevokeCredit(currentAdmin)).isFalse();
        }
    }

    @Nested
    @DisplayName("Linked Article Update Helper Tests")
    class LinkedArticleUpdateHelperTests {

        @Test
        @DisplayName("hasLinkedArticleUpdate returns false when no ARTICLE_UPDATE_LINKED event")
        void shouldReturnFalseWhenNoLinkedEvent() {
            AdminWikiContributionDetailDTO dto = createDetail(
                    WikiContributionStatus.REVIEWING,
                    null,
                    null,
                    null
            );

            assertThat(dto.hasLinkedArticleUpdate()).isFalse();
            assertThat(dto.getLatestLinkedArticleContentVersion()).isNull();
        }

        @Test
        @DisplayName("hasLinkedArticleUpdate returns true and gives version when ARTICLE_UPDATE_LINKED event exists")
        void shouldReturnTrueAndVersionWhenLinkedEventExists() {
            AdminWikiContributionWorkflowEventDTO event = new AdminWikiContributionWorkflowEventDTO(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    com.universe.wiki.domain.contribution.WikiContributionEventType.ARTICLE_UPDATE_LINKED,
                    resolver(),
                    null,
                    2L,
                    null,
                    "Linked update note",
                    now
            );

            AdminWikiContributionDetailDTO dto = new AdminWikiContributionDetailDTO(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "CHARACTER",
                    "Trần Bình An",
                    "tran-binh-an",
                    1L,
                    contributor(),
                    WikiContributionContextType.TEXT_SELECTION,
                    WikiContributionType.WORDING,
                    "Lỗi diễn đạt",
                    "đoạn văn",
                    null,
                    null,
                    null,
                    WikiContributionStatus.REVIEWING,
                    1L,
                    now.minusSeconds(100),
                    now,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    resolver(),
                    now,
                    resolver(),
                    now,
                    1L,
                    null,
                    List.of(event),
                    null
            );

            assertThat(dto.hasLinkedArticleUpdate()).isTrue();
            assertThat(dto.getLatestLinkedArticleContentVersion()).isEqualTo(2L);
        }
    }
}
