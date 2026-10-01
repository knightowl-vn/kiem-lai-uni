package com.universe.interaction.entry.community;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.entry.community.dto.CommunityDiscussionFeedResponseDTO;
import com.universe.interaction.entry.community.dto.CommunityRootCommentDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityPostDiscussionQueryCoordinator Unit Tests")
class CommunityPostDiscussionQueryCoordinatorTest {

    @Mock
    private CommunityPostQueryPort communityPostQueryPort;

    @Mock
    private GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;

    @Mock
    private ListCommentRootsUseCase listCommentRootsUseCase;

    @Mock
    private CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase;

    @Mock
    private GetCommentThreadUseCase getCommentThreadUseCase;

    @Mock
    private ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;

    private CommunityPostDiscussionQueryCoordinator coordinator;

    private static final UUID POST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REPLY_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID AUTHOR_1_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AUTHOR_2_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @BeforeEach
    void setUp() {
        coordinator = new CommunityPostDiscussionQueryCoordinator(
                communityPostQueryPort,
                getCommentTargetMetricsUseCase,
                listCommentRootsUseCase,
                countVisibleActiveRepliesByRootIdsUseCase,
                getCommentThreadUseCase,
                validateCommentTargetScopeUseCase,
                userIdentityContract,
                getBatchReactionSummariesUseCase
        );
    }

    private CommunityPostPublicDTO mockPublicPost() {
        return new CommunityPostPublicDTO(
                POST_ID,
                AUTHOR_1_ID,
                "Test post caption",
                null,
                1,
                NOW,
                NOW
        );
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when arguments are invalid")
    void shouldThrowWhenArgumentsAreInvalid() {
        assertThatThrownBy(() -> coordinator.getDiscussionFeed(null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postId cannot be null");

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(POST_ID, -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page cannot be negative");

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(POST_ID, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be greater than zero");

        assertThatThrownBy(() -> coordinator.getCommentThread(null, ROOT_1_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postId cannot be null");

        assertThatThrownBy(() -> coordinator.getCommentThread(POST_ID, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rootCommentId cannot be null");
    }

    @Test
    @DisplayName("Should throw CommentTargetNotEligibleException when post does not exist or is not public")
    void shouldThrowWhenPostNotFound() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(POST_ID, 0, 10))
                .isInstanceOf(CommentTargetNotEligibleException.class);

        assertThatThrownBy(() -> coordinator.getCommentThread(POST_ID, ROOT_1_ID, null))
                .isInstanceOf(CommentTargetNotEligibleException.class);

        verify(listCommentRootsUseCase, never()).execute(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should return empty feed when no active roots exist")
    void shouldReturnEmptyFeedWhenNoActiveRootsExist() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));
        CommentTarget target = CommentTarget.communityPost(POST_ID);
        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(0L, 0L));

        when(listCommentRootsUseCase.execute(target, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(), 0, 10, false));

        CommunityDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(POST_ID, 0, 10);

        assertThat(response.roots()).isEmpty();
        assertThat(response.commentCount()).isEqualTo(0L);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.hasNext()).isFalse();

        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
        verify(getBatchReactionSummariesUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("Should return feed with root comments and reply counts without materializing reply bodies (zero reply overfetch)")
    void shouldReturnFeedWithRootsAndReplyCountWithoutMaterializingReplyBodies() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));
        CommentTarget target = CommentTarget.communityPost(POST_ID);
        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1L, 6L));

        Comment rootComment = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root comment body", NOW);
        CommentReadItem rootItem = CommentReadItem.fromRoot(rootComment);

        when(listCommentRootsUseCase.execute(target, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 10, false));

        // Active reply count is 5 for ROOT_1_ID
        when(countVisibleActiveRepliesByRootIdsUseCase.execute(List.of(ROOT_1_ID)))
                .thenReturn(Map.of(ROOT_1_ID, 5L));

        // Identity bulk lookup must include ONLY the root author (zero reply authors)
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", "https://img/a1.png", "author_one")
                ));

        ReactionSummary rootReactionSummary = ReactionSummary.of(
                com.universe.interaction.domain.reaction.ReactionTarget.comment(ROOT_1_ID),
                Map.of(ReactionType.LIKE, 3L),
                null
        );
        when(getBatchReactionSummariesUseCase.execute(eq(ReactionTargetType.COMMENT), eq(List.of(ROOT_1_ID)), any()))
                .thenReturn(Map.of(ROOT_1_ID, rootReactionSummary));

        CommunityDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(POST_ID, 0, 10);

        assertThat(response.roots()).hasSize(1);
        assertThat(response.commentCount()).isEqualTo(6L);
        assertThat(response.hasNext()).isFalse();

        CommunityRootCommentDTO rootRow = response.roots().get(0);
        assertThat(rootRow.root().id()).isEqualTo(ROOT_1_ID);
        assertThat(rootRow.root().body()).isEqualTo("Root comment body");
        assertThat(rootRow.root().author().displayName()).isEqualTo("Author One");
        assertThat(rootRow.root().author().publicHandle()).isEqualTo("author_one");
        assertThat(rootRow.root().reactionSummary()).isNotNull();
        assertThat(rootRow.root().reactionSummary().totalCount()).isEqualTo(3);
        assertThat(rootRow.replyCount()).isEqualTo(5L);

        // Verify exactly 1 bulk identity call for ROOT authors ONLY (zero reply authors)
        verify(userIdentityContract).findPublicProfilesByIds(Set.of(AUTHOR_1_ID));
    }

    @Test
    @DisplayName("Should fetch single thread with deduplicated root and reply authors on demand")
    void shouldFetchSingleThreadWithDeduplicatedAuthors() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));

        CommentTarget expectedTarget = CommentTarget.communityPost(POST_ID);
        Comment rootComment = Comment.createRoot(ROOT_1_ID, expectedTarget, AUTHOR_1_ID, "Root body", NOW);
        Comment replyComment = Comment.createReply(REPLY_1_ID, rootComment, AUTHOR_2_ID, "Reply body", NOW.plusSeconds(30));

        CommentReadItem rootItem = CommentReadItem.fromRoot(rootComment);
        CommentReadItem replyItem = CommentReadItem.fromActiveReply(replyComment, AUTHOR_1_ID);
        CommentThreadView threadView = new CommentThreadView(rootItem, List.of(replyItem));

        when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(threadView);

        // Exactly one bulk call resolving deduplicated root author + reply authors
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null, "author_two")
                ));

        CommentThreadResponseDTO thread = coordinator.getCommentThread(POST_ID, ROOT_1_ID, null);

        verify(validateCommentTargetScopeUseCase).executeRoot(ROOT_1_ID, expectedTarget);
        assertThat(thread.root().id()).isEqualTo(ROOT_1_ID);
        assertThat(thread.root().author().displayName()).isEqualTo("Author One");
        assertThat(thread.root().author().publicHandle()).isEqualTo("author_one");
        assertThat(thread.replies()).hasSize(1);
        assertThat(thread.replies().get(0).id()).isEqualTo(REPLY_1_ID);
        assertThat(thread.replies().get(0).author().displayName()).isEqualTo("Author Two");
        assertThat(thread.replies().get(0).author().publicHandle()).isEqualTo("author_two");

        verify(userIdentityContract).findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID));
    }

    @Test
    @DisplayName("Should fail when root comment belongs to a different target")
    void shouldFailWhenTargetMismatch() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));

        CommentTarget expectedTarget = CommentTarget.communityPost(POST_ID);
        doThrow(new CommentNotFoundException("Target mismatch"))
                .when(validateCommentTargetScopeUseCase).executeRoot(ROOT_1_ID, expectedTarget);

        assertThatThrownBy(() -> coordinator.getCommentThread(POST_ID, ROOT_1_ID, null))
                .isInstanceOf(CommentNotFoundException.class);

        verify(getCommentThreadUseCase, never()).execute(any());
    }
}
