package com.universe.interaction.entry.screen;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.screen.dto.ScreenDiscussionFeedResponseDTO;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenTitleDiscussionQueryCoordinator Unit Tests")
class ScreenTitleDiscussionQueryCoordinatorTest {

    @Mock
    private ScreenTitleRepositoryPort screenTitleRepositoryPort;

    @Mock
    private GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;

    @Mock
    private ListCommentRootsUseCase listCommentRootsUseCase;

    @Mock
    private GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;

    @Mock
    private GetCommentThreadUseCase getCommentThreadUseCase;

    @Mock
    private ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;

    private ScreenTitleDiscussionQueryCoordinator coordinator;

    private static final UUID TITLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID REPLY_2_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID AUTHOR_1_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AUTHOR_2_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TOMBSTONE_AUTHOR_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @BeforeEach
    void setUp() {
        coordinator = new ScreenTitleDiscussionQueryCoordinator(
                screenTitleRepositoryPort,
                getCommentTargetMetricsUseCase,
                listCommentRootsUseCase,
                getCommentThreadsByRootIdsUseCase,
                getCommentThreadUseCase,
                validateCommentTargetScopeUseCase,
                userIdentityContract,
                getBatchReactionSummariesUseCase
        );
    }

    @Test
    @DisplayName("Unpublished title gates before Interaction query on feed")
    void shouldGateUnpublishedTitleBeforeInteractionQueryOnFeed() {
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(TITLE_ID, 0, 20, null, CommentSortMode.FEATURED))
                .isInstanceOf(PublishedScreenTitleNotFoundException.class);

        verify(getCommentTargetMetricsUseCase, never()).execute(any());
        verify(listCommentRootsUseCase, never()).execute(any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Unpublished title gates before Interaction query on single thread")
    void shouldGateUnpublishedTitleBeforeInteractionQueryOnThread() {
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.getCommentThread(TITLE_ID, ROOT_1_ID, null))
                .isInstanceOf(PublishedScreenTitleNotFoundException.class);

        verify(validateCommentTargetScopeUseCase, never()).executeRoot(any(), any());
        verify(getCommentThreadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Empty feed returns empty thread list and zero metrics without batch thread query")
    void shouldReturnEmptyFeedWhenNoComments() {
        ScreenTitle title = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(CommentTargetMetrics.EMPTY);
        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 20))
                .thenReturn(new CommentReadSlice(List.of(), 0, 20, false));

        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 20, null, CommentSortMode.FEATURED);

        assertThat(response.threads()).isEmpty();
        assertThat(response.threadCount()).isZero();
        assertThat(response.commentCount()).isZero();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.hasNext()).isFalse();

        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }

    @Test
    @DisplayName("Metrics propagated correctly and roots preserve Slice ordering")
    void shouldPropagateMetricsAndPreserveSliceOrdering() {
        ScreenTitle title = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(2, 5));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        Comment root2 = Comment.createRoot(ROOT_2_ID, target, AUTHOR_2_ID, "Root 2 Body", NOW.plusSeconds(10));
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentReadItem root2Item = CommentReadItem.fromRoot(root2);

        // Slice order: root2 then root1
        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 2))
                .thenReturn(new CommentReadSlice(List.of(root2Item, root1Item), 0, 2, false));

        CommentThreadView thread2View = new CommentThreadView(root2Item, List.of());
        CommentThreadView thread1View = new CommentThreadView(root1Item, List.of());

        // Batch returns thread views in reverse order (thread1View, then thread2View)
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_2_ID, ROOT_1_ID)))
                .thenReturn(List.of(thread1View, thread2View));

        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null, "author_two")
                ));

        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 2, null, CommentSortMode.FEATURED);

        assertThat(response.threadCount()).isEqualTo(2);
        assertThat(response.commentCount()).isEqualTo(5);
        assertThat(response.threads()).hasSize(2);
        // Preserves slice order: root2 first, then root1
        assertThat(response.threads().get(0).root().id()).isEqualTo(ROOT_2_ID);
        assertThat(response.threads().get(1).root().id()).isEqualTo(ROOT_1_ID);
    }

    @Test
    @DisplayName("Replies preserve generic thread ordering and tombstone does not resolve author profile")
    void shouldPreserveThreadOrderingAndSuppressTombstoneAuthorProfile() {
        ScreenTitle title = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 2));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);

        Comment reply1 = Comment.createReply(REPLY_1_ID, root1, AUTHOR_2_ID, "Reply 1 Body", NOW.plusSeconds(5));
        CommentReadItem reply1Item = CommentReadItem.fromActiveReply(reply1, root1.getAuthorUserId());

        Comment tombstoneReply = Comment.createReply(REPLY_2_ID, root1, TOMBSTONE_AUTHOR_ID, "Tombstone Body", NOW.plusSeconds(10));
        tombstoneReply.delete(NOW.plusSeconds(11));
        CommentReadItem tombstoneReplyItem = CommentReadItem.fromTombstoneReply(tombstoneReply, root1.getAuthorUserId());

        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(root1Item), 0, 10, false));

        CommentThreadView threadView = new CommentThreadView(root1Item, List.of(reply1Item, tombstoneReplyItem));
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                .thenReturn(List.of(threadView));

        // TOMBSTONE_AUTHOR_ID must NOT be included in identity contract request
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null, "author_two")
                ));

        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

        assertThat(response.threads()).hasSize(1);
        var thread = response.threads().get(0);
        assertThat(thread.replies()).hasSize(2);
        assertThat(thread.replies().get(0).id()).isEqualTo(REPLY_1_ID);
        assertThat(thread.replies().get(0).tombstone()).isFalse();
        assertThat(thread.replies().get(0).author().displayName()).isEqualTo("Author Two");

        assertThat(thread.replies().get(1).id()).isEqualTo(REPLY_2_ID);
        assertThat(thread.replies().get(1).tombstone()).isTrue();
        assertThat(thread.replies().get(1).author()).isNull();
        assertThat(thread.replies().get(1).body()).isNull();
    }

    @Test
    @DisplayName("Concurrent missing/deleted root from authoritative batch is omitted from feed")
    void shouldOmitConcurrentMissingRootFromFeed() {
        ScreenTitle title = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        Comment root2 = Comment.createRoot(ROOT_2_ID, target, AUTHOR_2_ID, "Root 2 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentReadItem root2Item = CommentReadItem.fromRoot(root2);

        // Slice returned root1 and root2
        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(root1Item, root2Item), 0, 10, false));

        // Batch only returned thread for root1 (root2 was deleted concurrently)
        CommentThreadView thread1View = new CommentThreadView(root1Item, List.of());
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID, ROOT_2_ID)))
                .thenReturn(List.of(thread1View));

        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().id()).isEqualTo(ROOT_1_ID);
    }

    @Test
    @DisplayName("Single-thread cross-target validation happens before thread retrieval")
    void shouldValidateTargetScopeBeforeThreadRetrieval() {
        ScreenTitle title = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentThreadView threadView = new CommentThreadView(root1Item, List.of());

        when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(threadView);
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

        CommentThreadResponseDTO response = coordinator.getCommentThread(TITLE_ID, ROOT_1_ID, null);

        verify(validateCommentTargetScopeUseCase).executeRoot(ROOT_1_ID, target);
        assertThat(response.root().id()).isEqualTo(ROOT_1_ID);
    }

    @Test
    @DisplayName("Feed queries batch reactions for active roots and active replies, omitting tombstones")
    void shouldQueryBatchReactionsForActiveCommentsInFeed() {
        ScreenTitle title = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
        UUID viewerId = UUID.fromString("99999999-9999-9999-9999-999999999999");

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 3));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);

        Comment reply1 = Comment.createReply(REPLY_1_ID, root1, AUTHOR_2_ID, "Reply 1 Body", NOW.plusSeconds(5));
        CommentReadItem reply1Item = CommentReadItem.fromActiveReply(reply1, root1.getAuthorUserId());

        Comment tombstoneReply = Comment.createReply(REPLY_2_ID, root1, TOMBSTONE_AUTHOR_ID, "Tombstone Body", NOW.plusSeconds(10));
        tombstoneReply.delete(NOW.plusSeconds(11));
        CommentReadItem tombstoneReplyItem = CommentReadItem.fromTombstoneReply(tombstoneReply, root1.getAuthorUserId());

        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(root1Item), 0, 10, false));

        CommentThreadView threadView = new CommentThreadView(root1Item, List.of(reply1Item, tombstoneReplyItem));
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                .thenReturn(List.of(threadView));

        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null, "author_two")
                ));

        // Reaction summaries for active root1 and active reply1 (tombstone omitted)
        when(getBatchReactionSummariesUseCase.execute(
                eq(ReactionTargetType.COMMENT),
                eq(List.of(ROOT_1_ID, REPLY_1_ID)),
                eq(viewerId)
        )).thenReturn(Map.of(
                ROOT_1_ID, ReactionSummary.of(ReactionTarget.comment(ROOT_1_ID), Map.of(ReactionType.LIKE, 3L), ReactionType.LIKE),
                REPLY_1_ID, ReactionSummary.of(ReactionTarget.comment(REPLY_1_ID), Map.of(ReactionType.LIKE, 1L), null)
        ));

        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, viewerId, CommentSortMode.FEATURED);

        var thread = response.threads().get(0);
        assertThat(thread.root().reactionSummary()).isNotNull();
        assertThat(thread.root().reactionSummary().totalCount()).isEqualTo(3L);
        assertThat(thread.root().reactionSummary().currentUserReaction()).isEqualTo("LIKE");

        assertThat(thread.replies().get(0).reactionSummary()).isNotNull();
        assertThat(thread.replies().get(0).reactionSummary().totalCount()).isEqualTo(1L);
        assertThat(thread.replies().get(0).reactionSummary().currentUserReaction()).isNull();

        // Tombstone reply has null reaction summary
        assertThat(thread.replies().get(1).reactionSummary()).isNull();
    }

    @Test
    @DisplayName("Single thread queries batch reactions for active root and active replies")
    void shouldQueryBatchReactionsForSingleThread() {
        ScreenTitle title = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
        UUID viewerId = UUID.fromString("99999999-9999-9999-9999-999999999999");

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        Comment reply1 = Comment.createReply(REPLY_1_ID, root1, AUTHOR_2_ID, "Reply 1 Body", NOW.plusSeconds(5));
        CommentReadItem reply1Item = CommentReadItem.fromActiveReply(reply1, root1.getAuthorUserId());

        CommentThreadView threadView = new CommentThreadView(root1Item, List.of(reply1Item));

        when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(threadView);
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null, "author_two")
                ));

        when(getBatchReactionSummariesUseCase.execute(
                eq(ReactionTargetType.COMMENT),
                eq(List.of(ROOT_1_ID, REPLY_1_ID)),
                eq(viewerId)
        )).thenReturn(Map.of(
                ROOT_1_ID, ReactionSummary.of(ReactionTarget.comment(ROOT_1_ID), Map.of(ReactionType.LIKE, 2L), null)
        ));

        CommentThreadResponseDTO response = coordinator.getCommentThread(TITLE_ID, ROOT_1_ID, viewerId);

        assertThat(response.root().reactionSummary()).isNotNull();
        assertThat(response.root().reactionSummary().totalCount()).isEqualTo(2L);
        // Reply reaction summary is null because it was not in the returned map
        assertThat(response.replies().get(0).reactionSummary()).isNull();
    }

    @Test
    @DisplayName("Reaction query failure degrades gracefully without failing feed load")
    void shouldDegradeGracefullyWhenReactionQueryFails() {
        ScreenTitle title = Mockito.mock(ScreenTitle.class);
        when(screenTitleRepositoryPort.findPublishedById(TITLE_ID)).thenReturn(Optional.of(title));
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);

        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(root1Item), 0, 10, false));

        CommentThreadView thread1View = new CommentThreadView(root1Item, List.of());
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                .thenReturn(List.of(thread1View));

        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

        when(getBatchReactionSummariesUseCase.execute(any(), any(), any()))
                .thenThrow(new RuntimeException("Reaction service unavailable"));

        ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().reactionSummary()).isNull();
    }
}
