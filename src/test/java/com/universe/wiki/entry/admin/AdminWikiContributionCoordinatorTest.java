package com.universe.wiki.entry.admin;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.contribution.query.GetWikiContributionAdminInboxUseCase;
import com.universe.wiki.application.contribution.query.WikiContributionAdminFilter;
import com.universe.wiki.application.contribution.query.WikiContributionAdminItem;
import com.universe.wiki.application.contribution.query.WikiContributionAdminPage;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionQueuePageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminWikiContributionCoordinatorTest {

    @Mock
    private GetWikiContributionAdminInboxUseCase useCase;

    @Mock
    private com.universe.wiki.application.contribution.query.GetWikiContributionAdminDetailUseCase detailUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    private AdminWikiContributionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new AdminWikiContributionCoordinator(useCase, detailUseCase, userIdentityContract);
    }

    @Test
    @DisplayName("Constructor enforces non-null dependencies")
    void constructorEnforcesNonNull() {
        assertThatThrownBy(() -> new AdminWikiContributionCoordinator(null, detailUseCase, userIdentityContract))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminWikiContributionCoordinator(useCase, null, userIdentityContract))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminWikiContributionCoordinator(useCase, detailUseCase, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getInboxPage enriches contributor profile in 1 bulk query to Identity and preserves pagination metadata")
    void shouldEnrichContributorProfileInOneBulkQuery() {
        UUID user1Id = UUID.randomUUID();
        UUID user2Id = UUID.randomUUID();

        WikiContributionAdminItem item1 = new WikiContributionAdminItem(
                UUID.randomUUID(),
                WikiContributionStatus.NEW,
                WikiContributionType.INCORRECT_INFORMATION,
                WikiContributionContextType.GENERAL,
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                user1Id,
                "Lỗi thông tin nhân vật...",
                false,
                false,
                0,
                Instant.now()
        );

        WikiContributionAdminItem item2 = new WikiContributionAdminItem(
                UUID.randomUUID(),
                WikiContributionStatus.NEW,
                WikiContributionType.MISSING_INFORMATION,
                WikiContributionContextType.TEXT_SELECTION,
                UUID.randomUUID(),
                "REALM",
                "Cảnh giới tu luyện",
                "canh-gioi-tu-luyen",
                2L,
                user2Id,
                "Thiếu chi tiết cảnh giới thứ 5...",
                true,
                true,
                2,
                Instant.now()
        );

        WikiContributionAdminPage page = new WikiContributionAdminPage(
                List.of(item1, item2),
                0,
                20,
                42L,
                3,
                true,
                false
        );

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                null,
                0,
                20
        );

        when(useCase.getInboxPage(filter)).thenReturn(page);

        // Mock Identity bulk response: user1 found, user2 not found (unresolved)
        UserPublicProfileDTO user1Profile = new UserPublicProfileDTO(
                user1Id,
                "Độc Giả 1",
                "https://example.com/avatar1.jpg"
        );
        when(userIdentityContract.findPublicProfilesByIds(Set.of(user1Id, user2Id)))
                .thenReturn(Map.of(user1Id, user1Profile));

        AdminWikiContributionQueuePageDTO result = coordinator.getInboxPage(filter);

        assertThat(result.items()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(42L);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isFalse();

        // Row 1: resolved contributor
        assertThat(result.items().get(0).item()).isEqualTo(item1);
        assertThat(result.items().get(0).contributor().userId()).isEqualTo(user1Id);
        assertThat(result.items().get(0).contributor().displayName()).isEqualTo("Độc Giả 1");
        assertThat(result.items().get(0).contributor().avatarUrl()).isEqualTo("https://example.com/avatar1.jpg");
        assertThat(result.items().get(0).contributor().resolved()).isTrue();

        // Row 2: unresolved contributor (safe fallback)
        assertThat(result.items().get(1).item()).isEqualTo(item2);
        assertThat(result.items().get(1).contributor().userId()).isEqualTo(user2Id);
        assertThat(result.items().get(1).contributor().displayName()).isNull();
        assertThat(result.items().get(1).contributor().resolved()).isFalse();

        verify(userIdentityContract).findPublicProfilesByIds(Set.of(user1Id, user2Id));
    }

    @Test
    @DisplayName("getInboxPage preserves raw pagination metadata when items are empty")
    void shouldPreserveMetadataWhenItemsAreEmpty() {
        WikiContributionAdminPage emptyPage = new WikiContributionAdminPage(
                List.of(),
                0,
                20,
                0L,
                0,
                true,
                true
        );

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                null,
                0,
                20
        );

        when(useCase.getInboxPage(filter)).thenReturn(emptyPage);

        AdminWikiContributionQueuePageDTO result = coordinator.getInboxPage(filter);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(0L);
        assertThat(result.totalPages()).isEqualTo(0);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
        verifyNoInteractions(userIdentityContract);
    }

    @Test
    @DisplayName("getInboxPage returns safe empty DTO when rawPage is null")
    void shouldHandleNullRawPage() {
        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                WikiContributionStatus.NEW,
                null,
                null,
                0,
                20
        );

        when(useCase.getInboxPage(filter)).thenReturn(null);

        AdminWikiContributionQueuePageDTO result = coordinator.getInboxPage(filter);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(0L);
        assertThat(result.totalPages()).isEqualTo(0);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
        verifyNoInteractions(userIdentityContract);
    }

    @Test
    @DisplayName("getNewContributionCount delegates to useCase")
    void shouldDelegateNewCount() {
        when(useCase.getCountByStatus(WikiContributionStatus.NEW)).thenReturn(7L);

        long count = coordinator.getNewContributionCount();

        assertThat(count).isEqualTo(7L);
        verify(useCase).getCountByStatus(WikiContributionStatus.NEW);
    }

    @Test
    @DisplayName("getDetail enriches contributor and resolver in 1 bulk query to Identity")
    void shouldEnrichDetailContributorAndResolver() {
        UUID contributionId = UUID.randomUUID();
        UUID contributorUserId = UUID.randomUUID();
        UUID resolverUserId = UUID.randomUUID();

        com.universe.wiki.domain.contribution.WikiContributionSource source =
                com.universe.wiki.domain.contribution.WikiContributionSource.reconstitute(
                        UUID.randomUUID(),
                        contributionId,
                        0,
                        com.universe.wiki.domain.contribution.WikiContributionSourceType.INTERNAL,
                        "/wiki/character/tran-binh-an",
                        Instant.now()
                );

        com.universe.wiki.application.contribution.query.WikiContributionAdminDetail detail =
                new com.universe.wiki.application.contribution.query.WikiContributionAdminDetail(
                        contributionId,
                        UUID.randomUUID(),
                        "CHARACTER",
                        "Trần Bình An",
                        "tran-binh-an",
                        1L,
                        contributorUserId,
                        WikiContributionContextType.TEXT_SELECTION,
                        WikiContributionType.WORDING,
                        "Sửa lỗi chính tả",
                        "văn bản chọn",
                        "tiền tố",
                        "hậu tố",
                        "#heading",
                        WikiContributionStatus.RESOLVED,
                        2L,
                        Instant.now().minusSeconds(100),
                        Instant.now(),
                        List.of(source),
                        "Đã sửa trong bản mới",
                        resolverUserId,
                        Instant.now(),
                        2L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome.APPLIED,
                        List.of(),
                        null
                );

        when(detailUseCase.execute(contributionId)).thenReturn(detail);

        UserPublicProfileDTO contributorProfile = new UserPublicProfileDTO(
                contributorUserId, "Contributor Name", "https://example.com/avatar.jpg"
        );
        UserPublicProfileDTO resolverProfile = new UserPublicProfileDTO(
                resolverUserId, "Admin Resolver", null
        );

        when(userIdentityContract.findPublicProfilesByIds(Set.of(contributorUserId, resolverUserId)))
                .thenReturn(Map.of(contributorUserId, contributorProfile, resolverUserId, resolverProfile));

        com.universe.wiki.entry.admin.dto.AdminWikiContributionDetailDTO result =
                coordinator.getDetail(contributionId);

        assertThat(result.contributionId()).isEqualTo(contributionId);
        assertThat(result.contributor().displayName()).isEqualTo("Contributor Name");
        assertThat(result.contributor().resolved()).isTrue();
        assertThat(result.resolver().displayName()).isEqualTo("Admin Resolver");
        assertThat(result.resolver().resolved()).isTrue();
        assertThat(result.sources()).hasSize(1);
        assertThat(result.resolutionNote()).isEqualTo("Đã sửa trong bản mới");
        assertThat(result.resolvedArticleContentVersion()).isEqualTo(2L);
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.hasSelectionEvidence()).isTrue();

        verify(userIdentityContract).findPublicProfilesByIds(Set.of(contributorUserId, resolverUserId));
    }

    @Test
    @DisplayName("getDetail enriches creditedBy in the single bulk Identity query when ACTIVE credit exists")
    void shouldEnrichActiveCreditInSingleBulkIdentityQuery() {
        UUID contributionId = UUID.randomUUID();
        UUID contributorUserId = UUID.randomUUID();
        UUID resolverUserId = UUID.randomUUID();
        UUID creditedByUserId = UUID.randomUUID();
        Instant now = Instant.now();

        com.universe.wiki.application.contribution.query.WikiContributionAdminCredit adminCredit =
                new com.universe.wiki.application.contribution.query.WikiContributionAdminCredit(
                        com.universe.wiki.domain.credit.CreditStatus.ACTIVE,
                        creditedByUserId,
                        now,
                        "Đóng góp chuẩn xác",
                        null,
                        null,
                        null
                );

        com.universe.wiki.application.contribution.query.WikiContributionAdminDetail detail =
                new com.universe.wiki.application.contribution.query.WikiContributionAdminDetail(
                        contributionId,
                        UUID.randomUUID(),
                        "CHARACTER",
                        "Trần Bình An",
                        "tran-binh-an",
                        1L,
                        contributorUserId,
                        WikiContributionContextType.TEXT_SELECTION,
                        WikiContributionType.WORDING,
                        "Sửa lỗi chính tả",
                        "văn bản chọn",
                        "tiền tố",
                        "hậu tố",
                        "#heading",
                        WikiContributionStatus.RESOLVED,
                        2L,
                        now.minusSeconds(100),
                        now,
                        List.of(),
                        "Đã sửa trong bản mới",
                        resolverUserId,
                        now,
                        2L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome.APPLIED,
                        List.of(),
                        adminCredit
                );

        when(detailUseCase.execute(contributionId)).thenReturn(detail);

        UserPublicProfileDTO contributorProfile = new UserPublicProfileDTO(
                contributorUserId, "Contributor Name", "https://example.com/avatar.jpg"
        );
        UserPublicProfileDTO resolverProfile = new UserPublicProfileDTO(
                resolverUserId, "Admin Resolver", null
        );
        UserPublicProfileDTO creditedByProfile = new UserPublicProfileDTO(
                creditedByUserId, "Credit Admin", "https://example.com/admin.jpg"
        );

        when(userIdentityContract.findPublicProfilesByIds(Set.of(contributorUserId, resolverUserId, creditedByUserId)))
                .thenReturn(Map.of(
                        contributorUserId, contributorProfile,
                        resolverUserId, resolverProfile,
                        creditedByUserId, creditedByProfile
                ));

        com.universe.wiki.entry.admin.dto.AdminWikiContributionDetailDTO result =
                coordinator.getDetail(contributionId);

        assertThat(result.contributionId()).isEqualTo(contributionId);
        assertThat(result.contributor().userId()).isEqualTo(contributorUserId);
        assertThat(result.credit()).isNotNull();
        assertThat(result.credit().isActive()).isTrue();
        assertThat(result.credit().creditedBy().userId()).isEqualTo(creditedByUserId);
        assertThat(result.credit().creditedBy().displayName()).isEqualTo("Credit Admin");
        assertThat(result.credit().creditNote()).isEqualTo("Đóng góp chuẩn xác");
        assertThat(result.credit().revokedBy()).isNull();

        verify(userIdentityContract).findPublicProfilesByIds(Set.of(contributorUserId, resolverUserId, creditedByUserId));
    }

    @Test
    @DisplayName("getDetail enriches revokedBy in the single bulk Identity query when REVOKED credit exists")
    void shouldEnrichRevokedCreditInSingleBulkIdentityQuery() {
        UUID contributionId = UUID.randomUUID();
        UUID contributorUserId = UUID.randomUUID();
        UUID resolverUserId = UUID.randomUUID();
        UUID creditedByUserId = UUID.randomUUID();
        UUID revokedByUserId = UUID.randomUUID();
        Instant now = Instant.now();

        com.universe.wiki.application.contribution.query.WikiContributionAdminCredit adminCredit =
                new com.universe.wiki.application.contribution.query.WikiContributionAdminCredit(
                        com.universe.wiki.domain.credit.CreditStatus.REVOKED,
                        creditedByUserId,
                        now.minusSeconds(3600),
                        "Đóng góp ban đầu",
                        revokedByUserId,
                        now,
                        "Phát hiện trùng lặp gian lận"
                );

        com.universe.wiki.application.contribution.query.WikiContributionAdminDetail detail =
                new com.universe.wiki.application.contribution.query.WikiContributionAdminDetail(
                        contributionId,
                        UUID.randomUUID(),
                        "CHARACTER",
                        "Trần Bình An",
                        "tran-binh-an",
                        1L,
                        contributorUserId,
                        WikiContributionContextType.TEXT_SELECTION,
                        WikiContributionType.WORDING,
                        "Sửa lỗi chính tả",
                        "văn bản chọn",
                        "tiền tố",
                        "hậu tố",
                        "#heading",
                        WikiContributionStatus.RESOLVED,
                        2L,
                        now.minusSeconds(100),
                        now,
                        List.of(),
                        "Đã sửa trong bản mới",
                        resolverUserId,
                        now,
                        2L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome.APPLIED,
                        List.of(),
                        adminCredit
                );

        when(detailUseCase.execute(contributionId)).thenReturn(detail);

        UserPublicProfileDTO contributorProfile = new UserPublicProfileDTO(
                contributorUserId, "Contributor Name", null
        );
        UserPublicProfileDTO resolverProfile = new UserPublicProfileDTO(
                resolverUserId, "Admin Resolver", null
        );
        UserPublicProfileDTO creditedByProfile = new UserPublicProfileDTO(
                creditedByUserId, "Credit Admin", null
        );
        UserPublicProfileDTO revokedByProfile = new UserPublicProfileDTO(
                revokedByUserId, "Super Admin Revoker", null
        );

        when(userIdentityContract.findPublicProfilesByIds(Set.of(contributorUserId, resolverUserId, creditedByUserId, revokedByUserId)))
                .thenReturn(Map.of(
                        contributorUserId, contributorProfile,
                        resolverUserId, resolverProfile,
                        creditedByUserId, creditedByProfile,
                        revokedByUserId, revokedByProfile
                ));

        com.universe.wiki.entry.admin.dto.AdminWikiContributionDetailDTO result =
                coordinator.getDetail(contributionId);

        assertThat(result.credit()).isNotNull();
        assertThat(result.credit().isRevoked()).isTrue();
        assertThat(result.credit().creditedBy().userId()).isEqualTo(creditedByUserId);
        assertThat(result.credit().revokedBy().userId()).isEqualTo(revokedByUserId);
        assertThat(result.credit().revokedBy().displayName()).isEqualTo("Super Admin Revoker");
        assertThat(result.credit().revocationReason()).isEqualTo("Phát hiện trùng lặp gian lận");

        verify(userIdentityContract).findPublicProfilesByIds(Set.of(contributorUserId, resolverUserId, creditedByUserId, revokedByUserId));
    }
}
