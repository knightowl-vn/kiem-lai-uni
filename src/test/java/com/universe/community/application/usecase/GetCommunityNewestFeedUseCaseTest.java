package com.universe.community.application.usecase;

import com.universe.community.application.cursor.CommunityPostKeysetCursor;
import com.universe.community.application.cursor.CommunityPostKeysetCursorCodec;
import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.community.domain.exception.CommunityPostValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommunityNewestFeedUseCase Unit Tests")
class GetCommunityNewestFeedUseCaseTest {

    @Mock
    private CommunityPostQueryPort postQueryPort;

    @Mock
    private CommunityPostEngagementMetricsPort engagementMetricsPort;

    private CommunityPostKeysetCursorCodec cursorCodec;
    private GetCommunityNewestFeedUseCase useCase;

    @BeforeEach
    void setUp() {
        cursorCodec = new CommunityPostKeysetCursorCodec();
        useCase = new GetCommunityNewestFeedUseCase(postQueryPort, engagementMetricsPort, cursorCodec);
    }

    @Test
    @DisplayName("Should return empty feed when no posts exist")
    void shouldReturnEmptyFeedWhenNoPosts() {
        when(postQueryPort.findNewestPostsKeyset(null, null, 21)).thenReturn(List.of());

        CommunityNewestFeedResponseDTO response = useCase.execute(null, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.size()).isEqualTo(20);

        verifyNoInteractions(engagementMetricsPort);
    }

    @Test
    @DisplayName("Should return first page without nextCursor when results <= size")
    void shouldReturnFirstPageWithoutNextCursorWhenUnderSize() {
        UUID p1Id = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostPublicDTO p1 = new CommunityPostPublicDTO(p1Id, authorId, "Post 1", null, 0, t1, t1);
        when(postQueryPort.findNewestPostsKeyset(null, null, 21)).thenReturn(List.of(p1));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1Id))).thenReturn(Map.of(
                p1Id, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(5L, 3L)
        ));

        CommunityNewestFeedResponseDTO response = useCase.execute(null, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.id()).isEqualTo(p1Id);
        assertThat(item.reactionCount()).isEqualTo(5L);
        assertThat(item.commentCount()).isEqualTo(3L);
        assertThat(item.engagementScore()).isEqualTo(8L); // 5 + 3 = 8
    }

    @Test
    @DisplayName("Should detect hasNext=true and build nextCursor when fetched size == limit (size + 1)")
    void shouldDetectHasNextAndBuildNextCursor() {
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        UUID p3Id = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        Instant t3 = Instant.parse("2026-09-30T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-30T09:00:00Z");
        Instant t1 = Instant.parse("2026-09-30T08:00:00Z");

        CommunityPostPublicDTO p1 = new CommunityPostPublicDTO(p1Id, authorId, "Post 1", null, 0, t3, t3);
        CommunityPostPublicDTO p2 = new CommunityPostPublicDTO(p2Id, authorId, "Post 2", null, 0, t2, t2);
        CommunityPostPublicDTO p3 = new CommunityPostPublicDTO(p3Id, authorId, "Post 3", null, 0, t1, t1);

        // Requesting size 2, so fetchLimit is 3
        when(postQueryPort.findNewestPostsKeyset(null, null, 3)).thenReturn(List.of(p1, p2, p3));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(p1Id, p2Id))).thenReturn(Map.of(
                p1Id, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(10L, 2L),
                p2Id, new CommunityPostEngagementMetricsPort.PostEngagementMetrics(0L, 0L)
        ));

        CommunityNewestFeedResponseDTO response = useCase.execute(null, 2);

        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotNull();

        // nextCursor must be derived from last returned item (p2)
        CommunityPostKeysetCursor decodedCursor = cursorCodec.decode(response.nextCursor());
        assertThat(decodedCursor).isNotNull();
        assertThat(decodedCursor.createdAt()).isEqualTo(t2);
        assertThat(decodedCursor.postId()).isEqualTo(p2Id);
    }

    @Test
    @DisplayName("Should decode valid cursor and pass to repository")
    void shouldDecodeValidCursorAndPassToRepository() {
        Instant cursorTime = Instant.parse("2026-09-30T09:00:00Z");
        UUID cursorPostId = UUID.randomUUID();
        String cursorStr = cursorCodec.encode(new CommunityPostKeysetCursor(cursorTime, cursorPostId));

        UUID nextPostId = UUID.randomUUID();
        Instant nextPostTime = Instant.parse("2026-09-30T08:00:00Z");
        CommunityPostPublicDTO nextPost = new CommunityPostPublicDTO(nextPostId, UUID.randomUUID(), "Next", null, 0, nextPostTime, nextPostTime);

        when(postQueryPort.findNewestPostsKeyset(eq(cursorTime), eq(cursorPostId), eq(21)))
                .thenReturn(List.of(nextPost));
        when(engagementMetricsPort.getEngagementMetricsForPosts(List.of(nextPostId)))
                .thenReturn(Map.of());

        CommunityNewestFeedResponseDTO response = useCase.execute(cursorStr, 20);

        assertThat(response.items()).hasSize(1);
        CommunityPostFeedItemDTO item = response.items().get(0);
        assertThat(item.id()).isEqualTo(nextPostId);
        // Default zero metrics when missing in map
        assertThat(item.reactionCount()).isEqualTo(0L);
        assertThat(item.commentCount()).isEqualTo(0L);
        assertThat(item.engagementScore()).isEqualTo(0L);

        verify(postQueryPort).findNewestPostsKeyset(cursorTime, cursorPostId, 21);
    }

    @Test
    @DisplayName("Should throw CommunityPostValidationException on size <= 0 or size > 50")
    void shouldValidateSizeBounds() {
        assertThatThrownBy(() -> useCase.execute(null, 0))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");

        assertThatThrownBy(() -> useCase.execute(null, -5))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");

        assertThatThrownBy(() -> useCase.execute(null, 51))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Size must be between 1 and 50.");
    }

    @Test
    @DisplayName("Should throw CommunityPostValidationException on malformed cursor")
    void shouldThrowOnMalformedCursor() {
        assertThatThrownBy(() -> useCase.execute("not-a-valid-cursor", 20))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Invalid cursor format.");
    }
}
