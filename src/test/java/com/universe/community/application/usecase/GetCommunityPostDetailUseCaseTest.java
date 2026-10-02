package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityAuthorProfileSummary;
import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort.PostEngagementMetrics;
import com.universe.community.application.service.CommunityPostFeedAuthorEnricher;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommunityPostDetailUseCase Unit Tests")
class GetCommunityPostDetailUseCaseTest {

    private static final UUID POST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID AUTHOR_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440001");
    private static final UUID VIEWER_ID = UUID.fromString("770e8400-e29b-41d4-a716-446655440002");

    @Mock
    private CommunityPostQueryPort postQueryPort;

    @Mock
    private CommunityPostEngagementMetricsPort engagementMetricsPort;

    @Mock
    private CommunityAuthorProfilePort authorProfilePort;

    private CommunityPostFeedAuthorEnricher authorEnricher;
    private GetCommunityPostDetailUseCase useCase;

    @BeforeEach
    void setUp() {
        authorEnricher = new CommunityPostFeedAuthorEnricher(authorProfilePort);
        useCase = new GetCommunityPostDetailUseCase(postQueryPort, engagementMetricsPort, authorEnricher);
    }

    @Test
    @DisplayName("1. Missing post returns Optional.empty without querying metrics or author enrichment")
    void missingPostReturnsEmpty() {
        when(postQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.empty());

        Optional<CommunityPostFeedItemDTO> result = useCase.execute(POST_ID, VIEWER_ID);

        assertThat(result).isEmpty();
        verify(postQueryPort).findPublicPostById(POST_ID);
        verifyNoInteractions(engagementMetricsPort);
        verifyNoInteractions(authorProfilePort);
    }

    @Test
    @DisplayName("1b. Null postId returns Optional.empty immediately without any repository calls")
    void nullPostIdReturnsEmpty() {
        Optional<CommunityPostFeedItemDTO> result = useCase.execute(null, VIEWER_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(postQueryPort);
        verifyNoInteractions(engagementMetricsPort);
        verifyNoInteractions(authorProfilePort);
    }

    @Test
    @DisplayName("2. Guest existing post requests metrics with null viewer, returning counts and null reaction")
    void guestExistingPostRetrievesMetricsWithNullViewer() {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        CommunityPostPublicDTO publicPost = new CommunityPostPublicDTO(
                POST_ID, AUTHOR_ID, "Test caption", null, "/media/assets/1/content", 0, now, now
        );
        when(postQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(publicPost));
        when(engagementMetricsPort.getEngagementMetricsForPosts(eq(List.of(POST_ID))))
                .thenReturn(Map.of(POST_ID, new PostEngagementMetrics(5L, 3L, null)));
        when(authorProfilePort.findAuthorProfilesByIds(eq(Set.of(AUTHOR_ID))))
                .thenReturn(Map.of(AUTHOR_ID, new CommunityAuthorProfileSummary(AUTHOR_ID, "tieu_viem", "Tiêu Viêm", "/avatars/tv.png")));

        Optional<CommunityPostFeedItemDTO> result = useCase.execute(POST_ID);

        assertThat(result).isPresent();
        CommunityPostFeedItemDTO item = result.get();
        assertThat(item.id()).isEqualTo(POST_ID);
        assertThat(item.authorUserId()).isEqualTo(AUTHOR_ID);
        assertThat(item.caption()).isEqualTo("Test caption");
        assertThat(item.reactionCount()).isEqualTo(5L);
        assertThat(item.commentCount()).isEqualTo(3L);
        assertThat(item.engagementScore()).isEqualTo(8L);
        assertThat(item.currentUserReaction()).isNull();
        assertThat(item.authorDisplayName()).isEqualTo("Tiêu Viêm");
        assertThat(item.authorPublicHandle()).isEqualTo("tieu_viem");
        assertThat(item.authorAvatarUrl()).isEqualTo("/avatars/tv.png");

        verify(engagementMetricsPort).getEngagementMetricsForPosts(List.of(POST_ID));
        verify(authorProfilePort).findAuthorProfilesByIds(Set.of(AUTHOR_ID));
    }

    @Test
    @DisplayName("3. Authenticated viewer existing post passes exact viewerUserId and preserves currentUserReaction")
    void authenticatedViewerExistingPostPreservesReaction() {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        CommunityPostPublicDTO publicPost = new CommunityPostPublicDTO(
                POST_ID, AUTHOR_ID, "Võ đạo thông thần", null, null, 1, now, now
        );
        when(postQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(publicPost));
        when(engagementMetricsPort.getEngagementMetricsForPosts(eq(List.of(POST_ID)), eq(VIEWER_ID)))
                .thenReturn(Map.of(POST_ID, new PostEngagementMetrics(12L, 4L, "LIKE")));
        when(authorProfilePort.findAuthorProfilesByIds(eq(Set.of(AUTHOR_ID))))
                .thenReturn(Map.of(AUTHOR_ID, new CommunityAuthorProfileSummary(AUTHOR_ID, "han_lap", "Hàn Lập", null)));

        Optional<CommunityPostFeedItemDTO> result = useCase.execute(POST_ID, VIEWER_ID);

        assertThat(result).isPresent();
        CommunityPostFeedItemDTO item = result.get();
        assertThat(item.id()).isEqualTo(POST_ID);
        assertThat(item.reactionCount()).isEqualTo(12L);
        assertThat(item.commentCount()).isEqualTo(4L);
        assertThat(item.currentUserReaction()).isEqualTo("LIKE");
        assertThat(item.contentVersion()).isEqualTo(1);
        assertThat(item.authorDisplayName()).isEqualTo("Hàn Lập");
        assertThat(item.authorPublicHandle()).isEqualTo("han_lap");

        verify(engagementMetricsPort).getEngagementMetricsForPosts(List.of(POST_ID), VIEWER_ID);
    }

    @Test
    @DisplayName("4. Author enrichment preserves fields and handles missing author gracefully")
    void authorEnrichmentHandlesMissingAuthorGracefully() {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        CommunityPostPublicDTO publicPost = new CommunityPostPublicDTO(
                POST_ID, AUTHOR_ID, "Caption without author profile", null, null, 0, now, now
        );
        when(postQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(publicPost));
        when(engagementMetricsPort.getEngagementMetricsForPosts(eq(List.of(POST_ID)), eq(VIEWER_ID)))
                .thenReturn(Map.of(POST_ID, new PostEngagementMetrics(0L, 0L, null)));
        when(authorProfilePort.findAuthorProfilesByIds(eq(Set.of(AUTHOR_ID))))
                .thenReturn(Map.of()); // No summary found

        Optional<CommunityPostFeedItemDTO> result = useCase.execute(POST_ID, VIEWER_ID);

        assertThat(result).isPresent();
        CommunityPostFeedItemDTO item = result.get();
        assertThat(item.authorDisplayName()).isNull();
        assertThat(item.authorPublicHandle()).isNull();
        assertThat(item.authorAvatarUrl()).isNull();
        assertThat(item.caption()).isEqualTo("Caption without author profile");
    }

    @Test
    @DisplayName("5. Exactly one post is queried and no sibling or feed posts are loaded")
    void exactlyOnePostQueriedWithoutSiblingLoading() {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        CommunityPostPublicDTO publicPost = new CommunityPostPublicDTO(
                POST_ID, AUTHOR_ID, "Single post only", null, null, 0, now, now
        );
        when(postQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(publicPost));
        when(engagementMetricsPort.getEngagementMetricsForPosts(eq(List.of(POST_ID)), eq(VIEWER_ID)))
                .thenReturn(Map.of(POST_ID, new PostEngagementMetrics(1L, 0L, null)));
        when(authorProfilePort.findAuthorProfilesByIds(eq(Set.of(AUTHOR_ID))))
                .thenReturn(Map.of(AUTHOR_ID, new CommunityAuthorProfileSummary(AUTHOR_ID, "handle", "Name", null)));

        useCase.execute(POST_ID, VIEWER_ID);

        verify(postQueryPort).findPublicPostById(POST_ID);
        verify(postQueryPort, never()).findNewestPostsKeyset(any(), any(), any(Integer.class));
        verify(postQueryPort, never()).findAuthoredPostsKeyset(any(), any(), any(), any(Integer.class));
    }
}
