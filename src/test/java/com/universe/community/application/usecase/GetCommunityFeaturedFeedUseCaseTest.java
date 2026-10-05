package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.contracts.dto.CommunityFeaturedFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRankingCandidateDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.community.domain.exception.CommunityPostValidationException;
import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityAuthorProfileSummary;
import com.universe.community.application.service.CommunityPostFeedAuthorEnricher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommunityFeaturedFeedUseCase Unit Tests")
class GetCommunityFeaturedFeedUseCaseTest {

    @Mock
    private CommunityPostQueryPort postQueryPort;

    @Mock
    private CommunityPostEngagementMetricsPort engagementMetricsPort;

    @Mock
    private CommunityAuthorProfilePort authorProfilePort;

    private CommunityPostFeedAuthorEnricher authorEnricher;
    private GetCommunityFeaturedFeedUseCase useCase;

    @BeforeEach
    void setUp() {
        authorEnricher = new CommunityPostFeedAuthorEnricher(authorProfilePort);
        useCase = new GetCommunityFeaturedFeedUseCase(postQueryPort, engagementMetricsPort, authorEnricher);
    }

    @Test
    @DisplayName("A. Empty feed -> items=[], totalItems=0, totalPages=0, hasNext=false, zero outbound queries")
    void shouldReturnEmptyFeedWhenNoPostsExist() {
        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of());

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalItems()).isEqualTo(0L);
        assertThat(response.totalPages()).isEqualTo(0);
        assertThat(response.hasNext()).isFalse();

        verifyNoInteractions(engagementMetricsPort);
    }

    @Test
    @DisplayName("B. Single post with zero engagement -> reactionCount=0, commentCount=0, score=0")
    void shouldRankSinglePostWithZeroEngagement() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(postId, now)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(postId))).thenReturn(Map.of());
        when(postQueryPort.findPublicPostsByIds(List.of(postId))).thenReturn(List.of(
                new CommunityPostPublicDTO(postId, authorId, "Solo post", null, 0, now, now)
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 20);

        assertThat(response.items()).hasSize(1);
        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.id()).isEqualTo(postId);
        assertThat(item.reactionCount()).isEqualTo(0L);
        assertThat(item.commentCount()).isEqualTo(0L);
        assertThat(item.engagementScore()).isEqualTo(0L);
        assertThat(response.totalItems()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    @DisplayName("C. EngagementScore = reactionCount + commentCount")
    void shouldComputeEngagementScoreSummingReactionsAndComments() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(postId, now)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(postId))).thenReturn(Map.of(
                postId, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(12L, 7L)
        ));
        when(postQueryPort.findPublicPostsByIds(List.of(postId))).thenReturn(List.of(
                new CommunityPostPublicDTO(postId, authorId, "Engaged post", null, 0, now, now)
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 20);

        assertThat(response.items()).hasSize(1);
        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.reactionCount()).isEqualTo(12L);
        assertThat(item.commentCount()).isEqualTo(7L);
        assertThat(item.engagementScore()).isEqualTo(19L); // 12 + 7 = 19
    }

    @Test
    @DisplayName("D. Primary ranking: higher engagement score ranks first")
    void shouldRankHigherScoreFirst() {
        UUID p1 = UUID.randomUUID(); // score 10
        UUID p2 = UUID.randomUUID(); // score 50
        UUID p3 = UUID.randomUUID(); // score 25
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(p1, now),
                new CommunityPostRankingCandidateDTO(p2, now),
                new CommunityPostRankingCandidateDTO(p3, now)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1, p2, p3))).thenReturn(Map.of(
                p1, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(5L, 5L),   // 10
                p2, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(30L, 20L), // 50
                p3, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(20L, 5L)   // 25
        ));
        when(postQueryPort.findPublicPostsByIds(List.of(p2, p3, p1))).thenReturn(List.of(
                new CommunityPostPublicDTO(p1, UUID.randomUUID(), "P1", null, 0, now, now),
                new CommunityPostPublicDTO(p2, UUID.randomUUID(), "P2", null, 0, now, now),
                new CommunityPostPublicDTO(p3, UUID.randomUUID(), "P3", null, 0, now, now)
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 20);

        assertThat(response.items()).extracting(CommunityPostFeedItemDTO::id)
                .containsExactly(p2, p3, p1);
    }

    @Test
    @DisplayName("E. First tie-break: same engagementScore -> createdAt DESC")
    void shouldTieBreakOnSameScoreUsingCreatedAtDesc() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        Instant tOlder = Instant.parse("2026-09-30T08:00:00Z");
        Instant tNewer = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(older, tOlder),
                new CommunityPostRankingCandidateDTO(newer, tNewer)
        ));
        // Same score 10
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(older, newer))).thenReturn(Map.of(
                older, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(5L, 5L),
                newer, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(5L, 5L)
        ));
        when(postQueryPort.findPublicPostsByIds(List.of(newer, older))).thenReturn(List.of(
                new CommunityPostPublicDTO(older, UUID.randomUUID(), "Older", null, 0, tOlder, tOlder),
                new CommunityPostPublicDTO(newer, UUID.randomUUID(), "Newer", null, 0, tNewer, tNewer)
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 20);

        assertThat(response.items()).extracting(CommunityPostFeedItemDTO::id)
                .containsExactly(newer, older);
    }

    @Test
    @DisplayName("F. Second tie-break: same engagementScore and same createdAt -> canonical UUID textual id DESC")
    void shouldTieBreakOnSameScoreAndCreatedAtUsingCanonicalUuidTextualIdDesc() {
        UUID idLow = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID idHigh = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Instant tShared = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(idLow, tShared),
                new CommunityPostRankingCandidateDTO(idHigh, tShared)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(idLow, idHigh))).thenReturn(Map.of());
        when(postQueryPort.findPublicPostsByIds(List.of(idHigh, idLow))).thenReturn(List.of(
                new CommunityPostPublicDTO(idLow, UUID.randomUUID(), "Low", null, 0, tShared, tShared),
                new CommunityPostPublicDTO(idHigh, UUID.randomUUID(), "High", null, 0, tShared, tShared)
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 20);

        // idHigh ("b...") must precede idLow ("a...") in id DESC ordering
        assertThat(response.items()).extracting(CommunityPostFeedItemDTO::id)
                .containsExactly(idHigh, idLow);
    }

    @Test
    @DisplayName("G. ALL-TIME correctness: older high-engagement post ranks above newer zero-engagement post")
    void shouldPreserveAllTimeCorrectnessOlderHighEngagementOverNewerLowEngagement() {
        UUID oldViral = UUID.randomUUID();
        UUID newQuiet = UUID.randomUUID();
        Instant tOld = Instant.parse("2026-01-01T00:00:00Z");
        Instant tNew = Instant.parse("2026-09-30T12:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(newQuiet, tNew),
                new CommunityPostRankingCandidateDTO(oldViral, tOld)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(newQuiet, oldViral))).thenReturn(Map.of(
                oldViral, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(100L, 50L), // 150
                newQuiet, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(0L, 0L)     // 0
        ));
        when(postQueryPort.findPublicPostsByIds(List.of(oldViral, newQuiet))).thenReturn(List.of(
                new CommunityPostPublicDTO(oldViral, UUID.randomUUID(), "Viral", null, 0, tOld, tOld),
                new CommunityPostPublicDTO(newQuiet, UUID.randomUUID(), "Quiet", null, 0, tNew, tNew)
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 20);

        assertThat(response.items()).extracting(CommunityPostFeedItemDTO::id)
                .containsExactly(oldViral, newQuiet);
    }

    @Test
    @DisplayName("H & I. Page 0 vs Page 1 traversal with complete candidate set")
    void shouldHandlePaginationAcrossPages() {
        UUID p1 = UUID.randomUUID(); // score 30
        UUID p2 = UUID.randomUUID(); // score 20
        UUID p3 = UUID.randomUUID(); // score 10
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        List<CommunityPostRankingCandidateDTO> candidates = List.of(
                new CommunityPostRankingCandidateDTO(p1, now),
                new CommunityPostRankingCandidateDTO(p2, now),
                new CommunityPostRankingCandidateDTO(p3, now)
        );
        when(postQueryPort.findAllRankingCandidates()).thenReturn(candidates);
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1, p2, p3))).thenReturn(Map.of(
                p1, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(20L, 10L),
                p2, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(15L, 5L),
                p3, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(5L, 5L)
        ));

        // --- Page 0 (size 2) -> winning IDs: [p1, p2] ---
        when(postQueryPort.findPublicPostsByIds(List.of(p1, p2))).thenReturn(List.of(
                new CommunityPostPublicDTO(p1, UUID.randomUUID(), "P1", null, 0, now, now),
                new CommunityPostPublicDTO(p2, UUID.randomUUID(), "P2", null, 0, now, now)
        ));

        CommunityFeaturedFeedResponseDTO page0 = useCase.execute(0, 2);
        assertThat(page0.items()).extracting(CommunityPostFeedItemDTO::id).containsExactly(p1, p2);
        assertThat(page0.page()).isEqualTo(0);
        assertThat(page0.size()).isEqualTo(2);
        assertThat(page0.totalItems()).isEqualTo(3L);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.hasNext()).isTrue();

        // --- Page 1 (size 2) -> winning IDs: [p3] ---
        when(postQueryPort.findPublicPostsByIds(List.of(p3))).thenReturn(List.of(
                new CommunityPostPublicDTO(p3, UUID.randomUUID(), "P3", null, 0, now, now)
        ));

        CommunityFeaturedFeedResponseDTO page1 = useCase.execute(1, 2);
        assertThat(page1.items()).extracting(CommunityPostFeedItemDTO::id).containsExactly(p3);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);
        assertThat(page1.totalItems()).isEqualTo(3L);
        assertThat(page1.totalPages()).isEqualTo(2);
        assertThat(page1.hasNext()).isFalse();
    }

    @Test
    @DisplayName("J. Out-of-range page -> empty items, accurate totals, hasNext=false, no winner hydration query")
    void shouldHandleOutOfRangePageGracefully() {
        UUID p1 = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(p1, now)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1))).thenReturn(Map.of());

        CommunityFeaturedFeedResponseDTO response = useCase.execute(5, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(5);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalItems()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();

        verify(postQueryPort, times(0)).findPublicPostsByIds(any());
    }

    @Test
    @DisplayName("J1. Large page value (Integer.MAX_VALUE) -> safe arithmetic, no overflow, empty items, hasNext=false")
    void shouldHandleIntegerMaxValuePageGracefullyWithoutOverflow() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(p1, now),
                new CommunityPostRankingCandidateDTO(p2, now)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1, p2))).thenReturn(Map.of());

        CommunityFeaturedFeedResponseDTO response = useCase.execute(Integer.MAX_VALUE, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalItems()).isEqualTo(2L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();

        verify(postQueryPort, times(0)).findPublicPostsByIds(any());
    }

    @Test
    @DisplayName("J2. Total-page arithmetic proofs: 0 items -> 0 pages, size items -> 1 page, size+1 items -> 2 pages")
    void shouldCalculateTotalPagesCorrectlyAcrossExactBoundaries() {
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        // 0 items -> 0 pages
        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of());
        CommunityFeaturedFeedResponseDTO res0 = useCase.execute(0, 5);
        assertThat(res0.totalItems()).isEqualTo(0L);
        assertThat(res0.totalPages()).isEqualTo(0);

        // size items (5) -> 1 page
        List<CommunityPostRankingCandidateDTO> candidates5 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates5.add(new CommunityPostRankingCandidateDTO(UUID.randomUUID(), now));
        }
        when(postQueryPort.findAllRankingCandidates()).thenReturn(candidates5);
        when(engagementMetricsPort.getEngagementMetricsForPosts(any())).thenReturn(Map.of());
        when(postQueryPort.findPublicPostsByIds(any())).thenReturn(List.of());

        CommunityFeaturedFeedResponseDTO res5 = useCase.execute(0, 5);
        assertThat(res5.totalItems()).isEqualTo(5L);
        assertThat(res5.totalPages()).isEqualTo(1);

        // size + 1 items (6) -> 2 pages
        List<CommunityPostRankingCandidateDTO> candidates6 = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            candidates6.add(new CommunityPostRankingCandidateDTO(UUID.randomUUID(), now));
        }
        when(postQueryPort.findAllRankingCandidates()).thenReturn(candidates6);

        CommunityFeaturedFeedResponseDTO res6 = useCase.execute(0, 5);
        assertThat(res6.totalItems()).isEqualTo(6L);
        assertThat(res6.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("K, L, M, N. Validate page and size boundary constraints")
    void shouldValidatePageAndSizeConstraints() {
        assertThatThrownBy(() -> useCase.execute(-1, 20))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Page cannot be negative: -1");

        assertThatThrownBy(() -> useCase.execute(0, 0))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");

        assertThatThrownBy(() -> useCase.execute(0, -5))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");

        assertThatThrownBy(() -> useCase.execute(0, 51))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");
    }

    @Test
    @DisplayName("P. Chunk boundary M (500 candidates) -> exactly 1 bulk Interaction call")
    void shouldQuerySingleBatchForChunkBoundary500() {
        List<CommunityPostRankingCandidateDTO> candidates = new ArrayList<>(500);
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        for (int i = 0; i < 500; i++) {
            candidates.add(new CommunityPostRankingCandidateDTO(UUID.randomUUID(), now));
        }

        when(postQueryPort.findAllRankingCandidates()).thenReturn(candidates);
        when(engagementMetricsPort.getEngagementMetricsForPosts(any())).thenReturn(Map.of());
        when(postQueryPort.findPublicPostsByIds(any())).thenReturn(List.of());

        useCase.execute(0, 20);

        verify(engagementMetricsPort, times(1)).getEngagementMetricsForPosts(any());
    }

    @Test
    @DisplayName("Q. Chunk boundary M + 1 (501 candidates) -> exactly 2 bulk Interaction calls")
    void shouldQueryTwoBatchesForChunkBoundary501() {
        List<CommunityPostRankingCandidateDTO> candidates = new ArrayList<>(501);
        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        for (int i = 0; i < 501; i++) {
            candidates.add(new CommunityPostRankingCandidateDTO(UUID.randomUUID(), now));
        }

        when(postQueryPort.findAllRankingCandidates()).thenReturn(candidates);
        when(engagementMetricsPort.getEngagementMetricsForPosts(any())).thenReturn(Map.of());
        when(postQueryPort.findPublicPostsByIds(any())).thenReturn(List.of());

        useCase.execute(0, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(engagementMetricsPort, times(2)).getEngagementMetricsForPosts(captor.capture());

        List<Collection<UUID>> allBatches = captor.getAllValues();
        assertThat(allBatches.get(0)).hasSize(500);
        assertThat(allBatches.get(1)).hasSize(1);
    }

    @Test
    @DisplayName("R. Winner-only hydration: hydrates only winning page IDs")
    void shouldHydrateOnlyWinningPageIds() {
        UUID p1 = UUID.randomUUID(); // Winner 1
        UUID p2 = UUID.randomUUID(); // Winner 2
        UUID p3 = UUID.randomUUID(); // Non-winner
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(p1, now),
                new CommunityPostRankingCandidateDTO(p2, now),
                new CommunityPostRankingCandidateDTO(p3, now)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1, p2, p3))).thenReturn(Map.of(
                p1, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(10L, 0L),
                p2, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(5L, 0L),
                p3, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(0L, 0L)
        ));
        when(postQueryPort.findPublicPostsByIds(List.of(p1, p2))).thenReturn(List.of(
                new CommunityPostPublicDTO(p1, UUID.randomUUID(), "P1", null, 0, now, now),
                new CommunityPostPublicDTO(p2, UUID.randomUUID(), "P2", null, 0, now, now)
        ));

        // Request page 0, size 2 -> should only request p1 and p2 from hydration port
        useCase.execute(0, 2);

        verify(postQueryPort).findPublicPostsByIds(List.of(p1, p2));
    }

    @Test
    @DisplayName("S. Hydration result DB order does NOT distort computed FEATURED ranking order")
    void shouldPreserveFeaturedRankingOrderRegardlessOfHydrationDbOrder() {
        UUID winnerFirst = UUID.randomUUID(); // Score 100
        UUID winnerSecond = UUID.randomUUID(); // Score 50
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(winnerFirst, now),
                new CommunityPostRankingCandidateDTO(winnerSecond, now)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(winnerFirst, winnerSecond))).thenReturn(Map.of(
                winnerFirst, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(60L, 40L), // 100
                winnerSecond, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(30L, 20L) // 50
        ));
        // Database returns them in REVERSE order [winnerSecond, winnerFirst]
        when(postQueryPort.findPublicPostsByIds(List.of(winnerFirst, winnerSecond))).thenReturn(List.of(
                new CommunityPostPublicDTO(winnerSecond, UUID.randomUUID(), "Second", null, 0, now, now),
                new CommunityPostPublicDTO(winnerFirst, UUID.randomUUID(), "First", null, 0, now, now)
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 2);

        // Must still be ordered [winnerFirst, winnerSecond]
        assertThat(response.items()).extracting(CommunityPostFeedItemDTO::id)
                .containsExactly(winnerFirst, winnerSecond);
    }

    @Test
    @DisplayName("T. Concurrently hard-deleted post during hydration phase is safely omitted without crash")
    void shouldSafelyOmitConcurrentlyDeletedPostWithoutCrash() {
        UUID winner1 = UUID.randomUUID();
        UUID winner2Deleted = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-30T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-30T09:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(winner1, t1),
                new CommunityPostRankingCandidateDTO(winner2Deleted, t2)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(winner1, winner2Deleted))).thenReturn(Map.of());
        // Hydration only returns winner1 because winner2 was hard deleted concurrently
        when(postQueryPort.findPublicPostsByIds(List.of(winner1, winner2Deleted))).thenReturn(List.of(
                new CommunityPostPublicDTO(winner1, UUID.randomUUID(), "Surviving", null, 0, t1, t1)
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 2);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo(winner1);
    }

    @Test
    @DisplayName("U. Author metadata is enriched on the winning page items")
    void shouldEnrichWinningPageAuthorMetadata() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        when(postQueryPort.findAllRankingCandidates()).thenReturn(List.of(
                new CommunityPostRankingCandidateDTO(postId, now)
        ));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(postId))).thenReturn(Map.of(
                postId, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(5L, 2L)
        ));
        when(postQueryPort.findPublicPostsByIds(List.of(postId))).thenReturn(List.of(
                new CommunityPostPublicDTO(postId, authorId, "Featured caption", null, 0, now, now)
        ));
        when(authorProfilePort.findAuthorProfilesByIds(Set.of(authorId))).thenReturn(Map.of(
                authorId, new CommunityAuthorProfileSummary(authorId, "ninh_dao_gia", "Ninh Diêu", "https://cdn.example.com/ninh.jpg")
        ));

        CommunityFeaturedFeedResponseDTO response = useCase.execute(0, 20);

        assertThat(response.items()).hasSize(1);
        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.authorUserId()).isEqualTo(authorId);
        assertThat(item.authorPublicHandle()).isEqualTo("ninh_dao_gia");
        assertThat(item.authorDisplayName()).isEqualTo("Ninh Diêu");
        assertThat(item.authorAvatarUrl()).isEqualTo("https://cdn.example.com/ninh.jpg");
    }
}
