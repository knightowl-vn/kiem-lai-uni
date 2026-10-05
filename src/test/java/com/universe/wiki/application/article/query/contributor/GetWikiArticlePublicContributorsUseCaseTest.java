package com.universe.wiki.application.article.query.contributor;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.ports.WikiArticlePublicContributorQueryPort;
import com.universe.wiki.contracts.dto.WikiPublicContributorDTO;
import com.universe.wiki.contracts.dto.WikiPublicContributorsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("Rejects null dependencies in constructor")
    void shouldRejectNullDependenciesInConstructor() {
        assertThatThrownBy(() -> new GetWikiArticlePublicContributorsUseCase(null, userIdentityContract))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiArticlePublicContributorQueryPort");

        assertThatThrownBy(() -> new GetWikiArticlePublicContributorsUseCase(publicContributorQueryPort, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("UserIdentityContract");
    }

    @Test
    @DisplayName("Returns empty result and does NOT invoke Identity contract when aggregate query result is empty")
    void shouldReturnEmptyResultWithoutCallingIdentityWhenAggregatesEmpty() {
        UUID articleId = UUID.randomUUID();
        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(List.of());

        WikiPublicContributorsResult result = useCase.execute(articleId);

        assertThat(result.contributors()).isEmpty();
        assertThat(result.candidateLimitReached()).isFalse();
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }

    @Test
    @DisplayName("Returns empty result when articleId is null")
    void shouldReturnEmptyResultWhenArticleIdIsNull() {
        WikiPublicContributorsResult result = useCase.execute(null);

        assertThat(result.contributors()).isEmpty();
        assertThat(result.candidateLimitReached()).isFalse();
        verify(publicContributorQueryPort, never()).findActiveContributorsByArticleId(any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }

    @Test
    @DisplayName("Performs exactly ONE bulk Identity lookup and maps public DTOs with candidateLimitReached false when <= 50")
    void shouldPerformSingleBulkIdentityLookupAndMapPublicDTOs() {
        UUID articleId = UUID.randomUUID();
        UUID user1Id = UUID.randomUUID();
        UUID user2Id = UUID.randomUUID();

        Instant now = Instant.now();
        List<WikiArticlePublicContributorAggregate> aggregates = List.of(
                new WikiArticlePublicContributorAggregate(user1Id, 3L, now),
                new WikiArticlePublicContributorAggregate(user2Id, 1L, now.minusSeconds(60))
        );
        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(aggregates);

        Map<UUID, UserPublicProfileDTO> profileMap = Map.of(
                user1Id, new UserPublicProfileDTO(user1Id, "Trần Văn A", "https://avatar.com/a.jpg", "tran_van_a"),
                user2Id, new UserPublicProfileDTO(user2Id, "Lê Thị B", null, "le_thi_b")
        );
        when(userIdentityContract.findPublicProfilesByIds(Set.of(user1Id, user2Id)))
                .thenReturn(profileMap);

        WikiPublicContributorsResult result = useCase.execute(articleId);

        assertThat(result.candidateLimitReached()).isFalse();
        assertThat(result.contributors()).hasSize(2);
        assertThat(result.contributors().get(0).displayName()).isEqualTo("Trần Văn A");
        assertThat(result.contributors().get(0).avatarUrl()).isEqualTo("https://avatar.com/a.jpg");
        assertThat(result.contributors().get(0).activeCreditCount()).isEqualTo(3L);

        assertThat(result.contributors().get(1).displayName()).isEqualTo("Lê Thị B");
        assertThat(result.contributors().get(1).avatarUrl()).isNull();
        assertThat(result.contributors().get(1).activeCreditCount()).isEqualTo(1L);

        verify(userIdentityContract, times(1)).findPublicProfilesByIds(Set.of(user1Id, user2Id));
    }

    @Test
    @DisplayName("51 candidates fetched with all resolving -> renders exactly 50 contributors and candidateLimitReached is true")
    void shouldCapAt50AndSetLimitReachedWhen51CandidatesAllResolve() {
        UUID articleId = UUID.randomUUID();
        List<WikiArticlePublicContributorAggregate> aggregates = new ArrayList<>();
        Map<UUID, UserPublicProfileDTO> profileMap = new HashMap<>();

        Instant now = Instant.now();
        for (int i = 1; i <= 51; i++) {
            UUID userId = UUID.randomUUID();
            aggregates.add(new WikiArticlePublicContributorAggregate(userId, (long) i, now.minusSeconds(i * 10)));
            profileMap.put(userId, new UserPublicProfileDTO(userId, "Contributor " + i, null, "contributor_" + i));
        }

        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(aggregates);
        when(userIdentityContract.findPublicProfilesByIds(profileMap.keySet()))
                .thenReturn(profileMap);

        WikiPublicContributorsResult result = useCase.execute(articleId);

        assertThat(result.candidateLimitReached()).isTrue();
        assertThat(result.contributors()).hasSize(50);
        assertThat(result.contributors().get(0).displayName()).isEqualTo("Contributor 1");
        assertThat(result.contributors().get(49).displayName()).isEqualTo("Contributor 50");
    }

    @Test
    @DisplayName("51 candidates fetched with 1st missing -> 51st candidate backfills to reach 50 rendered contributors")
    void shouldBackfillWith51stCandidateWhenOneMissingProfile() {
        UUID articleId = UUID.randomUUID();
        List<WikiArticlePublicContributorAggregate> aggregates = new ArrayList<>();
        Map<UUID, UserPublicProfileDTO> profileMap = new HashMap<>();

        Instant now = Instant.now();
        UUID firstUserId = UUID.randomUUID();
        aggregates.add(new WikiArticlePublicContributorAggregate(firstUserId, 100L, now));
        // first user is NOT added to profileMap (missing in Identity)

        for (int i = 2; i <= 51; i++) {
            UUID userId = UUID.randomUUID();
            aggregates.add(new WikiArticlePublicContributorAggregate(userId, (long) i, now.minusSeconds(i * 10)));
            profileMap.put(userId, new UserPublicProfileDTO(userId, "Contributor " + i, null, "contributor_" + i));
        }

        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(aggregates);
        when(userIdentityContract.findPublicProfilesByIds(any()))
                .thenReturn(profileMap);

        WikiPublicContributorsResult result = useCase.execute(articleId);

        assertThat(result.candidateLimitReached()).isTrue();
        assertThat(result.contributors()).hasSize(50);
        // The 51st candidate was used to backfill the 50th slot
        assertThat(result.contributors().get(0).displayName()).isEqualTo("Contributor 2");
        assertThat(result.contributors().get(49).displayName()).isEqualTo("Contributor 51");
    }

    @Test
    @DisplayName("51 candidates fetched with several missing -> renders fewer than 50 and candidateLimitReached is true")
    void shouldReturnFewerThan50AndSetLimitReachedWhenSeveralMissing() {
        UUID articleId = UUID.randomUUID();
        List<WikiArticlePublicContributorAggregate> aggregates = new ArrayList<>();
        Map<UUID, UserPublicProfileDTO> profileMap = new HashMap<>();

        Instant now = Instant.now();
        // 51 candidates, but only 45 profiles resolved
        for (int i = 1; i <= 51; i++) {
            UUID userId = UUID.randomUUID();
            aggregates.add(new WikiArticlePublicContributorAggregate(userId, (long) i, now.minusSeconds(i * 10)));
            if (i <= 45) {
                profileMap.put(userId, new UserPublicProfileDTO(userId, "Contributor " + i, null, "contributor_" + i));
            }
        }

        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(aggregates);
        when(userIdentityContract.findPublicProfilesByIds(any()))
                .thenReturn(profileMap);

        WikiPublicContributorsResult result = useCase.execute(articleId);

        assertThat(result.candidateLimitReached()).isTrue();
        assertThat(result.contributors()).hasSize(45);
    }

    @Test
    @DisplayName("Gracefully returns empty result without throwing when Identity throws RuntimeException")
    void shouldReturnEmptyResultGracefullyWhenIdentityFails() {
        UUID articleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(publicContributorQueryPort.findActiveContributorsByArticleId(articleId))
                .thenReturn(List.of(new WikiArticlePublicContributorAggregate(userId, 1L, Instant.now())));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(userId)))
                .thenThrow(new RuntimeException("Identity service temporarily unavailable"));

        WikiPublicContributorsResult result = useCase.execute(articleId);

        assertThat(result.contributors()).isEmpty();
        assertThat(result.candidateLimitReached()).isFalse();
    }
}
