package com.universe.community.application.usecase;

import com.universe.community.application.cursor.CommunityPostKeysetCursor;
import com.universe.community.application.cursor.CommunityPostKeysetCursorCodec;
import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
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
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommunityAuthorPostsUseCase Unit Tests")
class GetCommunityAuthorPostsUseCaseTest {

    @Mock
    private CommunityPostQueryPort postQueryPort;

    @Mock
    private CommunityPostEngagementMetricsPort engagementMetricsPort;

    @Mock
    private CommunityAuthorProfilePort authorProfilePort;

    @Captor
    private ArgumentCaptor<List<UUID>> postIdsCaptor;

    private CommunityPostKeysetCursorCodec cursorCodec;
    private CommunityPostFeedAuthorEnricher authorEnricher;
    private GetCommunityAuthorPostsUseCase useCase;

    @BeforeEach
    void setUp() {
        cursorCodec = new CommunityPostKeysetCursorCodec();
        authorEnricher = new CommunityPostFeedAuthorEnricher(authorProfilePort);
        useCase = new GetCommunityAuthorPostsUseCase(postQueryPort, engagementMetricsPort, cursorCodec, authorEnricher);
    }

    @Test
    @DisplayName("Should throw CommunityPostValidationException when authorUserId is null")
    void shouldThrowCommunityPostValidationExceptionWhenAuthorUserIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, null, 20))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Author user ID cannot be null.");
    }

    @Test
    @DisplayName("Should return empty feed and skip engagement port when no authored posts exist")
    void shouldReturnEmptyFeedAndSkipEngagementPortWhenNoPosts() {
        UUID authorId = UUID.randomUUID();
        when(postQueryPort.findAuthoredPostsKeyset(authorId, null, null, 21)).thenReturn(List.of());

        CommunityNewestFeedResponseDTO response = useCase.execute(authorId, null, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.size()).isEqualTo(20);

        verifyNoInteractions(engagementMetricsPort);
    }

    @Test
    @DisplayName("Should return first page without nextCursor when results <= requested size")
    void shouldReturnFirstPageWithoutNextCursorWhenUnderSize() {
        UUID authorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostPublicDTO p1 = new CommunityPostPublicDTO(p1Id, authorId, "Post 1", null, 0, t1, t1);
        when(postQueryPort.findAuthoredPostsKeyset(authorId, null, null, 21)).thenReturn(List.of(p1));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1Id))).thenReturn(Map.of(
                p1Id, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(5L, 3L)
        ));

        CommunityNewestFeedResponseDTO response = useCase.execute(authorId, null, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.id()).isEqualTo(p1Id);
        assertThat(item.authorUserId()).isEqualTo(authorId);
        assertThat(item.reactionCount()).isEqualTo(5L);
        assertThat(item.commentCount()).isEqualTo(3L);
        assertThat(item.engagementScore()).isEqualTo(8L);
    }

    @Test
    @DisplayName("Should compute exact engagementScore as reactionCount + commentCount")
    void shouldComputeExactEngagementScoreFromReactionAndCommentCounts() {
        UUID authorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostPublicDTO p1 = new CommunityPostPublicDTO(p1Id, authorId, "Post 1", null, 0, t1, t1);
        when(postQueryPort.findAuthoredPostsKeyset(authorId, null, null, 21)).thenReturn(List.of(p1));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1Id))).thenReturn(Map.of(
                p1Id, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(7L, 4L)
        ));

        CommunityNewestFeedResponseDTO response = useCase.execute(authorId, null, 20);

        assertThat(response.items()).hasSize(1);
        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.reactionCount()).isEqualTo(7L);
        assertThat(item.commentCount()).isEqualTo(4L);
        assertThat(item.engagementScore()).isEqualTo(11L); // 7 + 4 = 11
    }

    @Test
    @DisplayName("Should default engagement metrics to zero when map entry is missing or null")
    void shouldDefaultEngagementMetricsToZeroWhenMapEntryMissingOrNull() {
        UUID authorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostPublicDTO p1 = new CommunityPostPublicDTO(p1Id, authorId, "Post 1", null, 0, t1, t1);
        when(postQueryPort.findAuthoredPostsKeyset(authorId, null, null, 21)).thenReturn(List.of(p1));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1Id))).thenReturn(Map.of());

        CommunityNewestFeedResponseDTO response = useCase.execute(authorId, null, 20);

        assertThat(response.items()).hasSize(1);
        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.reactionCount()).isEqualTo(0L);
        assertThat(item.commentCount()).isEqualTo(0L);
        assertThat(item.engagementScore()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should exclude sentinel row from returned items when fetched results exceed requested size")
    void shouldExcludeSentinelFromReturnedItemsWhenResultsExceedSize() {
        UUID authorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        UUID sentinelId = UUID.randomUUID();

        Instant t3 = Instant.parse("2026-09-30T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-30T09:00:00Z");
        Instant t1 = Instant.parse("2026-09-30T08:00:00Z");

        CommunityPostPublicDTO p1 = new CommunityPostPublicDTO(p1Id, authorId, "Post 1", null, 0, t3, t3);
        CommunityPostPublicDTO p2 = new CommunityPostPublicDTO(p2Id, authorId, "Post 2", null, 0, t2, t2);
        CommunityPostPublicDTO sentinel = new CommunityPostPublicDTO(sentinelId, authorId, "Sentinel", null, 0, t1, t1);

        // Requested size 2 -> fetchLimit 3
        when(postQueryPort.findAuthoredPostsKeyset(authorId, null, null, 3)).thenReturn(List.of(p1, p2, sentinel));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1Id, p2Id))).thenReturn(Map.of());

        CommunityNewestFeedResponseDTO response = useCase.execute(authorId, null, 2);

        // Returned items must be exactly size 2 and not contain the sentinel
        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting(CommunityPostFeedItemDTO::id).containsExactly(p1Id, p2Id);
        assertThat(response.items()).extracting(CommunityPostFeedItemDTO::id).doesNotContain(sentinelId);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("Should pass only returned page post IDs to engagementMetricsPort excluding sentinel")
    void shouldPassOnlyReturnedPagePostIdsToEngagementMetricsPortExcludingSentinel() {
        UUID authorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        UUID sentinelId = UUID.randomUUID();

        Instant t3 = Instant.parse("2026-09-30T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-30T09:00:00Z");
        Instant t1 = Instant.parse("2026-09-30T08:00:00Z");

        CommunityPostPublicDTO p1 = new CommunityPostPublicDTO(p1Id, authorId, "Post 1", null, 0, t3, t3);
        CommunityPostPublicDTO p2 = new CommunityPostPublicDTO(p2Id, authorId, "Post 2", null, 0, t2, t2);
        CommunityPostPublicDTO sentinel = new CommunityPostPublicDTO(sentinelId, authorId, "Sentinel", null, 0, t1, t1);

        when(postQueryPort.findAuthoredPostsKeyset(authorId, null, null, 3)).thenReturn(List.of(p1, p2, sentinel));
        when(engagementMetricsPort.getEngagementMetricsForPosts(postIdsCaptor.capture())).thenReturn(Map.of());

        useCase.execute(authorId, null, 2);

        List<UUID> capturedIds = postIdsCaptor.getValue();
        assertThat(capturedIds).containsExactly(p1Id, p2Id);
        assertThat(capturedIds).doesNotContain(sentinelId);
    }

    @Test
    @DisplayName("Should derive nextCursor from last returned item and never from sentinel")
    void shouldDeriveNextCursorFromLastReturnedItemNeverSentinel() {
        UUID authorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        UUID sentinelId = UUID.randomUUID();

        Instant t3 = Instant.parse("2026-09-30T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-30T09:00:00Z");
        Instant t1 = Instant.parse("2026-09-30T08:00:00Z");

        CommunityPostPublicDTO p1 = new CommunityPostPublicDTO(p1Id, authorId, "Post 1", null, 0, t3, t3);
        CommunityPostPublicDTO p2 = new CommunityPostPublicDTO(p2Id, authorId, "Post 2", null, 0, t2, t2);
        CommunityPostPublicDTO sentinel = new CommunityPostPublicDTO(sentinelId, authorId, "Sentinel", null, 0, t1, t1);

        when(postQueryPort.findAuthoredPostsKeyset(authorId, null, null, 3)).thenReturn(List.of(p1, p2, sentinel));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1Id, p2Id))).thenReturn(Map.of());

        CommunityNewestFeedResponseDTO response = useCase.execute(authorId, null, 2);

        assertThat(response.nextCursor()).isNotNull();
        CommunityPostKeysetCursor decodedCursor = cursorCodec.decode(response.nextCursor());
        assertThat(decodedCursor).isNotNull();
        assertThat(decodedCursor.publishedAt()).isEqualTo(t2);
        assertThat(decodedCursor.postId()).isEqualTo(p2Id);
        assertThat(decodedCursor.postId()).isNotEqualTo(sentinelId);
    }

    @Test
    @DisplayName("Should decode valid cursor and pass to repository")
    void shouldDecodeValidCursorAndPassToRepository() {
        UUID authorId = UUID.randomUUID();
        Instant cursorTime = Instant.parse("2026-09-30T09:00:00Z");
        UUID cursorPostId = UUID.randomUUID();
        String cursorStr = cursorCodec.encode(new CommunityPostKeysetCursor(cursorTime, cursorPostId));

        UUID nextPostId = UUID.randomUUID();
        Instant nextPostTime = Instant.parse("2026-09-30T08:00:00Z");
        CommunityPostPublicDTO nextPost = new CommunityPostPublicDTO(nextPostId, authorId, "Next", null, 0, nextPostTime, nextPostTime);

        when(postQueryPort.findAuthoredPostsKeyset(eq(authorId), eq(cursorTime), eq(cursorPostId), eq(21)))
                .thenReturn(List.of(nextPost));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(nextPostId)))
                .thenReturn(Map.of());

        CommunityNewestFeedResponseDTO response = useCase.execute(authorId, cursorStr, 20);

        assertThat(response.items()).hasSize(1);
        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.id()).isEqualTo(nextPostId);
        assertThat(item.authorUserId()).isEqualTo(authorId);

        verify(postQueryPort).findAuthoredPostsKeyset(authorId, cursorTime, cursorPostId, 21);
    }

    @Test
    @DisplayName("Should throw CommunityPostValidationException on size <= 0 or size > 50")
    void shouldValidateSizeBounds() {
        UUID authorId = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.execute(authorId, null, 0))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");

        assertThatThrownBy(() -> useCase.execute(authorId, null, -5))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");

        assertThatThrownBy(() -> useCase.execute(authorId, null, 51))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");
    }

    @Test
    @DisplayName("Should throw CommunityPostValidationException on malformed cursor")
    void shouldThrowOnMalformedCursor() {
        UUID authorId = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.execute(authorId, "not-a-valid-cursor", 20))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Invalid cursor format.");
    }
}
