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
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.entry.community.dto.CommunityDiscussionFeedResponseDTO;
import com.universe.interaction.entry.community.dto.CommunityRootCommentDTO;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService.ReactionEnrichmentResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreRootsOnlyFeedResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreSingleThreadResult;
import com.universe.interaction.entry.shared.DiscussionParticipants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

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
        assertThatThrownBy(() -> coordinator.getDiscussionFeed(null, 0, 10, null, CommentSortMode.FEATURED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postId cannot be null");

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(POST_ID, -1, 10, null, CommentSortMode.FEATURED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page cannot be negative");

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(POST_ID, 0, 0, null, CommentSortMode.FEATURED))
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

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.FEATURED))
                .isInstanceOf(CommentTargetNotEligibleException.class);

        assertThatThrownBy(() -> coordinator.getCommentThread(POST_ID, ROOT_1_ID, null))
                .isInstanceOf(CommentTargetNotEligibleException.class);

        verify(listCommentRootsUseCase, never()).execute(any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Should return empty feed when no active roots exist")
    void shouldReturnEmptyFeedWhenNoActiveRootsExist() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));
        CommentTarget target = CommentTarget.communityPost(POST_ID);
        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(0L, 0L));

        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(), 0, 10, false));

        CommunityDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.FEATURED);

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

        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
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

        CommunityDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.FEATURED);

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

    @Test
    @DisplayName("Feed preserves NEWEST sort, pagination, missing replyCount fallback, missing author fallback, and Reaction timeout fallback")
    void shouldPreservePaginationSortingFallbacksAndReactionIsolationOnFeed() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));
        CommentTarget target = CommentTarget.communityPost(POST_ID);
        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(3L, 8L));

        UUID root2Id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 body", NOW);
        Comment root2 = Comment.createRoot(root2Id, target, AUTHOR_2_ID, "Root 2 body", NOW.plusSeconds(10));

        when(listCommentRootsUseCase.execute(target, CommentSortMode.NEWEST, 2, 10))
                .thenReturn(new CommentReadSlice(List.of(CommentReadItem.fromRoot(root2), CommentReadItem.fromRoot(root1)), 2, 10, true));
        // Only root2 has replies in map; root1 defaults cleanly to 0L
        when(countVisibleActiveRepliesByRootIdsUseCase.execute(List.of(root2Id, ROOT_1_ID)))
                .thenReturn(Map.of(root2Id, 5L));
        // Only AUTHOR_1_ID returned; AUTHOR_2_ID uses canonical author fallback
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_2_ID, AUTHOR_1_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));
        when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(root2Id, ROOT_1_ID), AUTHOR_1_ID))
                .thenThrow(new QueryTimeoutException("Reaction DB timed out"));

        CommunityDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(POST_ID, 2, 10, AUTHOR_1_ID, CommentSortMode.NEWEST);

        assertThat(response.commentCount()).isEqualTo(8L);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.roots()).hasSize(2);

        CommunityRootCommentDTO first = response.roots().get(0);
        assertThat(first.root().id()).isEqualTo(root2Id);
        assertThat(first.root().author().displayName()).isEqualTo(CommentAuthorDTO.DEFAULT_DISPLAY_NAME);
        assertThat(first.root().canEdit()).isFalse();
        assertThat(first.root().reactionSummary()).isNull();
        assertThat(first.replyCount()).isEqualTo(5L);

        CommunityRootCommentDTO second = response.roots().get(1);
        assertThat(second.root().id()).isEqualTo(ROOT_1_ID);
        assertThat(second.root().author().displayName()).isEqualTo("Author One");
        assertThat(second.root().canEdit()).isTrue();
        assertThat(second.root().canDelete()).isTrue();
        assertThat(second.root().reactionSummary()).isNull();
        assertThat(second.replyCount()).isZero();

        // Feed must NEVER materialize threads or hydrate replies
        verify(getCommentThreadUseCase, never()).execute(any());
        verify(validateCommentTargetScopeUseCase, never()).executeRoot(any(), any());
    }

    @Test
    @DisplayName("Single thread redacts tombstone reply authors, sets capabilities, and degrades Reaction failures cleanly")
    void shouldHandleTombstonesCapabilitiesAndReactionTimeoutInSingleThread() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(mockPublicPost()));
        CommentTarget target = CommentTarget.communityPost(POST_ID);
        UUID tombstoneAuthorId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        UUID reply2Id = UUID.fromString("55555555-5555-5555-5555-555555555555");

        Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root body", NOW);
        Comment deletedParentReply = Comment.createReply(REPLY_1_ID, root, tombstoneAuthorId, "Deleted", NOW.plusSeconds(10));
        Comment activeDescendant = Comment.createReply(reply2Id, deletedParentReply, AUTHOR_2_ID, "Descendant", NOW.plusSeconds(20));
        deletedParentReply.delete(NOW.plusSeconds(30));

        CommentThreadView view = new CommentThreadView(
                CommentReadItem.fromRoot(root),
                List.of(
                        CommentReadItem.fromTombstoneReply(deletedParentReply, AUTHOR_1_ID),
                        CommentReadItem.fromActiveReply(activeDescendant, null)
                )
        );
        when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(view);
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));
        when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(ROOT_1_ID, reply2Id), AUTHOR_2_ID))
                .thenThrow(new QueryTimeoutException("Thread reaction timeout"));

        CommentThreadResponseDTO response = coordinator.getCommentThread(POST_ID, ROOT_1_ID, AUTHOR_2_ID);

        assertThat(response.root().id()).isEqualTo(ROOT_1_ID);
        assertThat(response.root().canEdit()).isFalse();
        assertThat(response.root().reactionSummary()).isNull();
        assertThat(response.replies()).hasSize(2);

        // Tombstone intermediate reply redacted completely
        assertThat(response.replies().get(0).tombstone()).isTrue();
        assertThat(response.replies().get(0).author()).isNull();
        assertThat(response.replies().get(0).authorUserId()).isNull();
        assertThat(response.replies().get(0).canEdit()).isFalse();

        // Active descendant retains ancestry while missing Identity profile falls back cleanly
        assertThat(response.replies().get(1).parentCommentId()).isEqualTo(REPLY_1_ID);
        assertThat(response.replies().get(1).replyToAuthorUserId()).isNull();
        assertThat(response.replies().get(1).author().displayName()).isEqualTo(CommentAuthorDTO.DEFAULT_DISPLAY_NAME);
        assertThat(response.replies().get(1).canEdit()).isTrue();
        assertThat(response.replies().get(1).canDelete()).isTrue();
        assertThat(response.replies().get(1).reactionSummary()).isNull();
        verify(userIdentityContract).findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID));
    }

    @Nested
    @DisplayName("Shared core delegation contracts")
    class SharedCoreDelegationTests {

        @Test
        @DisplayName("Primary constructor delegates feed strictly to readRootsOnlyFeed before Phase B Reaction lookup and never calls readThreadedFeed")
        void shouldDelegateFeedStrictlyToRootsOnlyCoreBeforeReactions() {
            DiscussionCoreReadService mockCore = Mockito.mock(DiscussionCoreReadService.class);
            CommentReactionEnrichmentService mockReact = Mockito.mock(CommentReactionEnrichmentService.class);
            CommunityPostDiscussionQueryCoordinator primary = new CommunityPostDiscussionQueryCoordinator(
                    communityPostQueryPort, mockCore, mockReact
            );

            CommentTarget target = CommentTarget.communityPost(POST_ID);
            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root body", NOW);
            CoreRootsOnlyFeedResult coreResult = new CoreRootsOnlyFeedResult(
                    1L,
                    4L,
                    0,
                    10,
                    false,
                    List.of(CommentReadItem.fromRoot(root)),
                    Map.of(ROOT_1_ID, 3L),
                    new DiscussionParticipants(Set.of(AUTHOR_1_ID), List.of(ROOT_1_ID)),
                    Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"))
            );

            when(mockCore.readRootsOnlyFeed(eq(target), eq(0), eq(10), eq(CommentSortMode.FEATURED), any()))
                    .thenReturn(coreResult);
            when(mockReact.loadCommentReactionSummaries("community post feed", List.of(ROOT_1_ID), AUTHOR_2_ID, false))
                    .thenReturn(new ReactionEnrichmentResult(
                            Map.of(ROOT_1_ID, new ReactionSummaryResponseDTO("COMMENT", ROOT_1_ID, Map.of("LIKE", 2L), 2L, "LIKE")),
                            false,
                            0L
                    ));

            CommunityDiscussionFeedResponseDTO feed = primary.getDiscussionFeed(POST_ID, 0, 10, AUTHOR_2_ID, CommentSortMode.FEATURED);

            assertThat(feed.commentCount()).isEqualTo(4L);
            assertThat(feed.roots()).hasSize(1);
            assertThat(feed.roots().get(0).replyCount()).isEqualTo(3L);
            assertThat(feed.roots().get(0).root().reactionSummary().currentUserReaction()).isEqualTo("LIKE");

            InOrder order = Mockito.inOrder(mockCore, mockReact);
            order.verify(mockCore).readRootsOnlyFeed(eq(target), eq(0), eq(10), eq(CommentSortMode.FEATURED), any());
            order.verify(mockReact).loadCommentReactionSummaries("community post feed", List.of(ROOT_1_ID), AUTHOR_2_ID, false);
            verify(mockCore, never()).readThreadedFeed(any(), any(int.class), any(int.class), any(), any(boolean.class), any(boolean.class), any());
            verify(mockCore, never()).readThreadedFeed(any(), any(int.class), any(int.class), any(), any(boolean.class), any(boolean.class), any(), any());
        }

        @Test
        @DisplayName("Primary constructor delegates single thread to readSingleThreadCore strictly before Reaction lookup")
        void shouldDelegateSingleThreadStrictlyBeforeReactions() {
            DiscussionCoreReadService mockCore = Mockito.mock(DiscussionCoreReadService.class);
            CommentReactionEnrichmentService mockReact = Mockito.mock(CommentReactionEnrichmentService.class);
            CommunityPostDiscussionQueryCoordinator primary = new CommunityPostDiscussionQueryCoordinator(
                    communityPostQueryPort, mockCore, mockReact
            );

            CommentTarget target = CommentTarget.communityPost(POST_ID);
            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Thread root", NOW);
            CommentThreadView view = new CommentThreadView(CommentReadItem.fromRoot(root), List.of());
            CoreSingleThreadResult<Void> singleCore = new CoreSingleThreadResult<>(
                    view,
                    new DiscussionParticipants(Set.of(AUTHOR_1_ID), List.of(ROOT_1_ID)),
                    Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")),
                    null
            );

            when(mockCore.readSingleThreadCore(eq(target), eq(ROOT_1_ID), any())).thenReturn(singleCore);
            when(mockReact.loadCommentReactionSummaries("community post thread", List.of(ROOT_1_ID), null, false))
                    .thenReturn(ReactionEnrichmentResult.EMPTY_SUCCESS);

            CommentThreadResponseDTO response = primary.getCommentThread(POST_ID, ROOT_1_ID, null);
            assertThat(response.root().id()).isEqualTo(ROOT_1_ID);

            InOrder order = Mockito.inOrder(mockCore, mockReact);
            order.verify(mockCore).readSingleThreadCore(eq(target), eq(ROOT_1_ID), any());
            order.verify(mockReact).loadCommentReactionSummaries("community post thread", List.of(ROOT_1_ID), null, false);
        }

        @Test
        @DisplayName("Empty feed skips Reaction enrichment and Phase A timeouts propagate cleanly")
        void shouldSkipReactionsWhenEmptyAndPropagatePhaseAFailure() {
            DiscussionCoreReadService mockCore = Mockito.mock(DiscussionCoreReadService.class);
            CommentReactionEnrichmentService mockReact = Mockito.mock(CommentReactionEnrichmentService.class);
            CommunityPostDiscussionQueryCoordinator primary = new CommunityPostDiscussionQueryCoordinator(
                    communityPostQueryPort, mockCore, mockReact
            );

            CommentTarget target = CommentTarget.communityPost(POST_ID);
            when(mockCore.readRootsOnlyFeed(eq(target), eq(0), eq(10), org.mockito.ArgumentMatchers.isNull(), any()))
                    .thenReturn(new CoreRootsOnlyFeedResult(0L, 5L, 0, 10, false, List.of(), Map.of(), DiscussionParticipants.EMPTY, Map.of()));

            CommunityDiscussionFeedResponseDTO emptyFeed = primary.getDiscussionFeed(POST_ID, 0, 10, null, null);
            assertThat(emptyFeed.roots()).isEmpty();
            assertThat(emptyFeed.commentCount()).isEqualTo(5L);
            verify(mockReact, never()).loadCommentReactionSummaries(any(), any(), any(), any(boolean.class));

            when(mockCore.readRootsOnlyFeed(eq(target), eq(0), eq(10), eq(CommentSortMode.NEWEST), any()))
                    .thenThrow(new QueryTimeoutException("Core query timed out"));
            assertThatThrownBy(() -> primary.getDiscussionFeed(POST_ID, 0, 10, null, CommentSortMode.NEWEST))
                    .isInstanceOf(QueryTimeoutException.class);
            verify(mockReact, never()).loadCommentReactionSummaries(any(), any(), any(), any(boolean.class));
        }
    }
}
