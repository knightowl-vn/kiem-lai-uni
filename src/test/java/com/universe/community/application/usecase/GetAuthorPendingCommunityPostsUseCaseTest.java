package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityAuthorProfileSummary;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.contracts.dto.AuthorPendingCommunityPostDTO;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetAuthorPendingCommunityPostsUseCase Unit Tests")
class GetAuthorPendingCommunityPostsUseCaseTest {

    @Mock
    private CommunityPostRepositoryPort postRepositoryPort;

    @Mock
    private CommunityAuthorProfilePort authorProfilePort;

    private GetAuthorPendingCommunityPostsUseCase useCase;

    private final UUID actorUserId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final Instant now = Instant.parse("2026-10-04T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetAuthorPendingCommunityPostsUseCase(postRepositoryPort, authorProfilePort);
    }

    @Test
    @DisplayName("Should return empty list immediately when actorUserId is null")
    void shouldReturnEmptyWhenActorIsNull() {
        List<AuthorPendingCommunityPostDTO> result = useCase.execute(null);

        assertThat(result).isEmpty();
        verify(postRepositoryPort, never()).findPendingReviewPostsByAuthor(any());
        verify(authorProfilePort, never()).findAuthorProfilesByIds(any());
    }

    @Test
    @DisplayName("Should return empty list when author has no pending posts")
    void shouldReturnEmptyWhenNoPendingPosts() {
        when(postRepositoryPort.findPendingReviewPostsByAuthor(actorUserId)).thenReturn(List.of());

        List<AuthorPendingCommunityPostDTO> result = useCase.execute(actorUserId);

        assertThat(result).isEmpty();
        verify(authorProfilePort, never()).findAuthorProfilesByIds(any());
    }

    @Test
    @DisplayName("Should retrieve and enrich pending posts with initial creation and edited re-review distinction")
    void shouldRetrieveAndEnrichPendingPosts() {
        UUID newPostId = UUID.randomUUID();
        UUID editedPostId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();

        // 1. Initial creation under PRE_MODERATION: publishedAt is null
        CommunityPost newPost = CommunityPost.rehydrate(
                newPostId, actorUserId, "New draft caption", imageId,
                CommunityPostStatus.PENDING_REVIEW, 0, now, now, null, now
        );

        // 2. Edited caption under PRE_MODERATION: publishedAt is preserved, status remains PUBLISHED, pendingCaption set
        Instant publishedEarlier = now.minusSeconds(3600);
        CommunityPost editedPost = CommunityPost.rehydrate(
                editedPostId, actorUserId, "Old public caption", "Edited caption awaiting re-review", null,
                CommunityPostStatus.PUBLISHED, 0, now.minusSeconds(7200), now, publishedEarlier, now
        );

        when(postRepositoryPort.findPendingReviewPostsByAuthor(actorUserId))
                .thenReturn(List.of(newPost, editedPost));

        when(authorProfilePort.findAuthorProfilesByIds(Set.of(actorUserId))).thenReturn(Map.of(
                actorUserId, new CommunityAuthorProfileSummary(actorUserId, "bach_tieu_thuan", "Bạch Tiểu Thuần", "/avatar.png")
        ));

        List<AuthorPendingCommunityPostDTO> result = useCase.execute(actorUserId);

        assertThat(result).hasSize(2);

        // First item: new post awaiting initial review
        AuthorPendingCommunityPostDTO item1 = result.get(0);
        assertThat(item1.id()).isEqualTo(newPostId);
        assertThat(item1.authorUserId()).isEqualTo(actorUserId);
        assertThat(item1.authorDisplayName()).isEqualTo("Bạch Tiểu Thuần");
        assertThat(item1.authorPublicHandle()).isEqualTo("bach_tieu_thuan");
        assertThat(item1.authorAvatarUrl()).isEqualTo("/avatar.png");
        assertThat(item1.caption()).isEqualTo("New draft caption");
        assertThat(item1.pendingCaption()).isNull();
        assertThat(item1.displayCaption()).isEqualTo("New draft caption");
        assertThat(item1.isPendingCaptionEdit()).isFalse();
        assertThat(item1.imageMediaAssetId()).isEqualTo(imageId);
        assertThat(item1.imageUrl()).isEqualTo("/media/assets/" + imageId + "/content");
        assertThat(item1.publishedAt()).isNull();
        assertThat(item1.isReReview()).isFalse();
        assertThat(item1.getStatusBadgeText()).isEqualTo("Đang chờ duyệt");

        // Second item: edited post awaiting re-review
        AuthorPendingCommunityPostDTO item2 = result.get(1);
        assertThat(item2.id()).isEqualTo(editedPostId);
        assertThat(item2.caption()).isEqualTo("Old public caption");
        assertThat(item2.pendingCaption()).isEqualTo("Edited caption awaiting re-review");
        assertThat(item2.displayCaption()).isEqualTo("Edited caption awaiting re-review");
        assertThat(item2.isPendingCaptionEdit()).isTrue();
        assertThat(item2.imageMediaAssetId()).isNull();
        assertThat(item2.imageUrl()).isNull();
        assertThat(item2.publishedAt()).isEqualTo(publishedEarlier);
        assertThat(item2.isReReview()).isTrue();
        assertThat(item2.getStatusBadgeText()).isEqualTo("Đang chờ duyệt chỉnh sửa");
    }

    @Test
    @DisplayName("Should use fallback display name when author profile summary is absent")
    void shouldUseFallbackWhenProfileMissing() {
        UUID postId = UUID.randomUUID();
        CommunityPost post = CommunityPost.rehydrate(
                postId, actorUserId, "Draft caption", null,
                CommunityPostStatus.PENDING_REVIEW, 0, now, now, null, now
        );

        when(postRepositoryPort.findPendingReviewPostsByAuthor(actorUserId)).thenReturn(List.of(post));
        when(authorProfilePort.findAuthorProfilesByIds(Set.of(actorUserId))).thenReturn(Map.of());

        List<AuthorPendingCommunityPostDTO> result = useCase.execute(actorUserId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).authorDisplayName()).isEqualTo("Người dùng");
        assertThat(result.get(0).authorPublicHandle()).isNull();
        assertThat(result.get(0).authorAvatarUrl()).isNull();
    }
}
