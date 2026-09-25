package com.universe.wiki.application.article.query.contributor;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.ports.WikiArticlePublicContributorQueryPort;
import com.universe.wiki.contracts.dto.WikiPublicContributorDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetWikiArticlePublicContributorsUseCase Unit Tests")
class GetWikiArticlePublicContributorsUseCaseTest {

    @Mock
    private WikiArticlePublicContributorQueryPort publicContributorQueryPort;

    @Mock
    private UserIdentityContract userIdentityContract;

    private GetWikiArticlePublicContributorsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetWikiArticlePublicContributorsUseCase(
                publicContributorQueryPort,
                userIdentityContract
        );
    }

    @Test
    @DisplayName("Returns empty list and does NOT invoke Identity contract when aggregate query result is empty")
    void shouldReturnEmptyListWithoutCallingIdentityWhenAggregatesEmpty() {
        UUID articleId = UUID.randomUUID();
        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(List.of());

        List<WikiPublicContributorDTO> result = useCase.execute(articleId);

        assertThat(result).isEmpty();
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }

    @Test
    @DisplayName("Returns empty list when articleId is null")
    void shouldReturnEmptyListWhenArticleIdIsNull() {
        List<WikiPublicContributorDTO> result = useCase.execute(null);

        assertThat(result).isEmpty();
        verify(publicContributorQueryPort, never()).findActiveContributorsByArticleId(any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }

    @Test
    @DisplayName("Performs exactly ONE bulk Identity lookup for multiple contributors and maps public DTOs correctly")
    void shouldPerformSingleBulkIdentityLookupAndMapPublicDTOs() {
        UUID articleId = UUID.randomUUID();
        UUID user1Id = UUID.randomUUID();
        UUID user2Id = UUID.randomUUID();

        List<WikiArticlePublicContributorAggregate> aggregates = List.of(
                new WikiArticlePublicContributorAggregate(user1Id, 3L),
                new WikiArticlePublicContributorAggregate(user2Id, 1L)
        );
        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(aggregates);

        Map<UUID, UserPublicProfileDTO> profileMap = Map.of(
                user1Id, new UserPublicProfileDTO(user1Id, "Trần Văn A", "https://avatar.com/a.jpg"),
                user2Id, new UserPublicProfileDTO(user2Id, "Lê Thị B", null)
        );
        when(userIdentityContract.findPublicProfilesByIds(Set.of(user1Id, user2Id)))
                .thenReturn(profileMap);

        List<WikiPublicContributorDTO> results = useCase.execute(articleId);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).displayName()).isEqualTo("Trần Văn A");
        assertThat(results.get(0).avatarUrl()).isEqualTo("https://avatar.com/a.jpg");
        assertThat(results.get(0).activeCreditCount()).isEqualTo(3L);

        assertThat(results.get(1).displayName()).isEqualTo("Lê Thị B");
        assertThat(results.get(1).avatarUrl()).isNull();
        assertThat(results.get(1).activeCreditCount()).isEqualTo(1L);

        verify(userIdentityContract, times(1)).findPublicProfilesByIds(Set.of(user1Id, user2Id));
    }

    @Test
    @DisplayName("Omits contributors whose public profile is missing or deleted from Identity")
    void shouldOmitContributorWhenPublicProfileNotFound() {
        UUID articleId = UUID.randomUUID();
        UUID user1Id = UUID.randomUUID();
        UUID deletedUserId = UUID.randomUUID();

        List<WikiArticlePublicContributorAggregate> aggregates = List.of(
                new WikiArticlePublicContributorAggregate(user1Id, 2L),
                new WikiArticlePublicContributorAggregate(deletedUserId, 1L)
        );
        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(aggregates);

        Map<UUID, UserPublicProfileDTO> profileMap = Map.of(
                user1Id, new UserPublicProfileDTO(user1Id, "Hoàng C", "https://avatar.com/c.png")
        );
        when(userIdentityContract.findPublicProfilesByIds(Set.of(user1Id, deletedUserId)))
                .thenReturn(profileMap);

        List<WikiPublicContributorDTO> results = useCase.execute(articleId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).displayName()).isEqualTo("Hoàng C");
        assertThat(results.get(0).activeCreditCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Gracefully returns empty list if IdentityContract throws exception")
    void shouldReturnEmptyListGracefullyWhenIdentityFails() {
        UUID articleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(List.of(new WikiArticlePublicContributorAggregate(userId, 1L)));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(userId)))
                .thenThrow(new RuntimeException("Identity service unavailable"));

        List<WikiPublicContributorDTO> results = useCase.execute(articleId);

        assertThat(results).isEmpty();
    }
}
