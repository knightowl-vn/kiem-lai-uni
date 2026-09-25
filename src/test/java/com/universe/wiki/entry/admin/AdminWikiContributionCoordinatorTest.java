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
    private UserIdentityContract userIdentityContract;

    private AdminWikiContributionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new AdminWikiContributionCoordinator(useCase, userIdentityContract);
    }

    @Test
    @DisplayName("Constructor enforces non-null dependencies")
    void constructorEnforcesNonNull() {
        assertThatThrownBy(() -> new AdminWikiContributionCoordinator(null, userIdentityContract))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminWikiContributionCoordinator(useCase, null))
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
}
