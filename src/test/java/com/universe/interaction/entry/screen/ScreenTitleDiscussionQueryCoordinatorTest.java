package com.universe.interaction.entry.screen;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
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
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.screen.dto.ScreenDiscussionFeedResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.screen.contracts.dto.ScreenEntryOriginDTO;
import com.universe.screen.contracts.interfaces.ScreenDiscussionContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenTitleDiscussionQueryCoordinator Unit Tests")
class ScreenTitleDiscussionQueryCoordinatorTest {

    @Mock
    private ScreenDiscussionContract screenDiscussionContract;

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
    private static final UUID EPISODE_1_ENTRY_ID = UUID.fromString("66666666-1111-1111-1111-111111111111");
    private static final UUID EPISODE_2_ENTRY_ID = UUID.fromString("66666666-2222-2222-2222-222222222222");
    private static final UUID FEATURE_ENTRY_ID = UUID.fromString("66666666-3333-3333-3333-333333333333");
    private static final UUID UNRELATED_ENTRY_ID = UUID.fromString("66666666-9999-9999-9999-999999999999");
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @BeforeEach
    void setUp() {
        coordinator = new ScreenTitleDiscussionQueryCoordinator(
                screenDiscussionContract,
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
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(TITLE_ID, 0, 20, null, CommentSortMode.FEATURED))
                .isInstanceOf(CommentTargetNotEligibleException.class);

        verify(getCommentTargetMetricsUseCase, never()).execute(any());
        verify(listCommentRootsUseCase, never()).execute(any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Unpublished title gates before Interaction query on single thread")
    void shouldGateUnpublishedTitleBeforeInteractionQueryOnThread() {
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.getCommentThread(TITLE_ID, ROOT_1_ID, null))
                .isInstanceOf(CommentTargetNotEligibleException.class);

        verify(validateCommentTargetScopeUseCase, never()).executeRoot(any(), any());
        verify(getCommentThreadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Empty feed returns empty thread list and zero metrics without batch thread query")
    void shouldReturnEmptyFeedWhenNoComments() {
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
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
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
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
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
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
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
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
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentThreadView threadView = new CommentThreadView(root1Item, List.of());

        when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(threadView);
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

        CommentThreadResponseDTO response = coordinator.getCommentThread(TITLE_ID, ROOT_1_ID, null);

        verify(validateCommentTargetScopeUseCase).executeRoot(ROOT_1_ID, target);
        verify(screenDiscussionContract, never()).findPublishedEntryOriginsByTitleId(any());
        verify(screenDiscussionContract, never()).findPublishedEntryOriginsByTitleIdAndEntryIds(any(), any());
        assertThat(response.root().id()).isEqualTo(ROOT_1_ID);
    }

    @Test
    @DisplayName("Feed queries batch reactions for active roots and active replies, omitting tombstones")
    void shouldQueryBatchReactionsForActiveCommentsInFeed() {
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
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
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
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
        when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
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

    @Nested
    @DisplayName("Episode Origin Badge Resolution Tests")
    class EpisodeOriginBadgeTests {

        @Test
        @DisplayName("Null origin produces no badge")
        void shouldOmitBadgeWhenCommentHasNullOrigin() {
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));

            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root without origin", NOW);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);

            when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 10, false));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(new CommentThreadView(rootItem, List.of())));
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                    .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

            ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

            assertThat(response.threads()).hasSize(1);
            assertThat(response.threads().get(0).root().origin()).isNull();
            verify(screenDiscussionContract, never()).findPublishedEntryOriginsByTitleId(any());
            verify(screenDiscussionContract, never()).findPublishedEntryOriginsByTitleIdAndEntryIds(any(), any());
        }

        @Test
        @DisplayName("Published Episode 1 origin formats as Tập 1 with slug")
        void shouldFormatEpisode1BadgeWhenOriginIsPublishedEpisode() {
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));

            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root on Episode 1", NOW, EPISODE_1_ENTRY_ID);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);

            when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 10, false));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(new CommentThreadView(rootItem, List.of())));
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                    .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

            when(screenDiscussionContract.findPublishedEntryOriginsByTitleIdAndEntryIds(TITLE_ID, Set.of(EPISODE_1_ENTRY_ID)))
                    .thenReturn(Map.of(EPISODE_1_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_1_ENTRY_ID, "tap-1", "Tập 1")));

            ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

            assertThat(response.threads()).hasSize(1);
            var origin = response.threads().get(0).root().origin();
            assertThat(origin).isNotNull();
            assertThat(origin.entryId()).isEqualTo(EPISODE_1_ENTRY_ID);
            assertThat(origin.badgeText()).isEqualTo("Tập 1");
            assertThat(origin.entrySlug()).isEqualTo("tap-1");
        }

        @Test
        @DisplayName("Published reply origin is preserved independently from root")
        void shouldPreservePublishedReplyOrigin() {
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 2));

            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "General root discussion", NOW);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);

            Comment reply = Comment.createReply(REPLY_1_ID, root, AUTHOR_2_ID, "Reply referencing Episode 2", NOW.plusSeconds(5), EPISODE_2_ENTRY_ID);
            CommentReadItem replyItem = CommentReadItem.fromActiveReply(reply, root.getAuthorUserId());

            when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 10, false));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(new CommentThreadView(rootItem, List.of(replyItem))));
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                    .thenReturn(Map.of(
                            AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"),
                            AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null, "author_two")
                    ));

            when(screenDiscussionContract.findPublishedEntryOriginsByTitleIdAndEntryIds(TITLE_ID, Set.of(EPISODE_2_ENTRY_ID)))
                    .thenReturn(Map.of(EPISODE_2_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_2_ENTRY_ID, "tap-2", "Tập 2")));

            ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

            var thread = response.threads().get(0);
            assertThat(thread.root().origin()).isNull();
            assertThat(thread.replies()).hasSize(1);
            var replyOrigin = thread.replies().get(0).origin();
            assertThat(replyOrigin).isNotNull();
            assertThat(replyOrigin.entryId()).isEqualTo(EPISODE_2_ENTRY_ID);
            assertThat(replyOrigin.badgeText()).isEqualTo("Tập 2");
            assertThat(replyOrigin.entrySlug()).isEqualTo("tap-2");
        }

        @Test
        @DisplayName("Unpublished or unrelated entry origin is not exposed")
        void shouldNotExposeUnpublishedOrUnrelatedEntryOrigin() {
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));

            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root with unrelated entry", NOW, UNRELATED_ENTRY_ID);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);

            when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 10, false));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(new CommentThreadView(rootItem, List.of())));
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                    .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

            when(screenDiscussionContract.findPublishedEntryOriginsByTitleIdAndEntryIds(TITLE_ID, Set.of(UNRELATED_ENTRY_ID)))
                    .thenReturn(Map.of());

            ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

            assertThat(response.threads()).hasSize(1);
            assertThat(response.threads().get(0).root().origin()).isNull();
        }

        @Test
        @DisplayName("Single-thread and paginated feed behavior agree on origin badges")
        void shouldAgreeOnSingleThreadAndPaginatedFeedOrigin() {
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root on Ep 1", NOW, EPISODE_1_ENTRY_ID);
            Comment reply = Comment.createReply(REPLY_1_ID, root, AUTHOR_2_ID, "Reply on Ep 2", NOW.plusSeconds(5), EPISODE_2_ENTRY_ID);

            CommentReadItem rootItem = CommentReadItem.fromRoot(root);
            CommentReadItem replyItem = CommentReadItem.fromActiveReply(reply, root.getAuthorUserId());
            CommentThreadView threadView = new CommentThreadView(rootItem, List.of(replyItem));

            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                    .thenReturn(Map.of(
                            AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"),
                            AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null, "author_two")
                    ));
            when(screenDiscussionContract.findPublishedEntryOriginsByTitleIdAndEntryIds(
                    TITLE_ID,
                    Set.of(EPISODE_1_ENTRY_ID, EPISODE_2_ENTRY_ID)
            )).thenReturn(Map.of(
                    EPISODE_1_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_1_ENTRY_ID, "tap-1", "Tập 1"),
                    EPISODE_2_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_2_ENTRY_ID, "tap-2", "Tập 2")
            ));

            // Feed execution
            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 2));
            when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 10, false));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(threadView));

            ScreenDiscussionFeedResponseDTO feedResponse = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

            // Single-thread execution
            when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(threadView);

            CommentThreadResponseDTO threadResponse = coordinator.getCommentThread(TITLE_ID, ROOT_1_ID, null);

            var feedRootOrigin = feedResponse.threads().get(0).root().origin();
            var threadRootOrigin = threadResponse.root().origin();
            assertThat(feedRootOrigin).isNotNull();
            assertThat(threadRootOrigin).isNotNull();
            assertThat(threadRootOrigin.entryId()).isEqualTo(feedRootOrigin.entryId()).isEqualTo(EPISODE_1_ENTRY_ID);
            assertThat(threadRootOrigin.badgeText()).isEqualTo(feedRootOrigin.badgeText()).isEqualTo("Tập 1");
            assertThat(threadRootOrigin.entrySlug()).isEqualTo(feedRootOrigin.entrySlug()).isEqualTo("tap-1");

            var feedReplyOrigin = feedResponse.threads().get(0).replies().get(0).origin();
            var threadReplyOrigin = threadResponse.replies().get(0).origin();
            assertThat(feedReplyOrigin).isNotNull();
            assertThat(threadReplyOrigin).isNotNull();
            assertThat(threadReplyOrigin.entryId()).isEqualTo(feedReplyOrigin.entryId()).isEqualTo(EPISODE_2_ENTRY_ID);
            assertThat(threadReplyOrigin.badgeText()).isEqualTo(feedReplyOrigin.badgeText()).isEqualTo("Tập 2");
            assertThat(threadReplyOrigin.entrySlug()).isEqualTo(feedReplyOrigin.entrySlug()).isEqualTo("tap-2");
        }

        @Test
        @DisplayName("FEATURE formats as Bản chiếu chính and never as fake Episode 1")
        void shouldFormatFeatureAsMainPresentationAndNeverFakeEpisode1() {
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root on feature film", NOW, FEATURE_ENTRY_ID);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);
            CommentThreadView threadView = new CommentThreadView(rootItem, List.of());

            when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(threadView);
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                    .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));
            when(screenDiscussionContract.findPublishedEntryOriginsByTitleIdAndEntryIds(TITLE_ID, Set.of(FEATURE_ENTRY_ID)))
                    .thenReturn(Map.of(FEATURE_ENTRY_ID, new ScreenEntryOriginDTO(FEATURE_ENTRY_ID, "ban-chieu-chinh", "Bản chiếu chính")));

            CommentThreadResponseDTO threadResponse = coordinator.getCommentThread(TITLE_ID, ROOT_1_ID, null);

            var origin = threadResponse.root().origin();
            assertThat(origin).isNotNull();
            assertThat(origin.entryId()).isEqualTo(FEATURE_ENTRY_ID);
            assertThat(origin.badgeText()).isEqualTo("Bản chiếu chính");
            assertThat(origin.badgeText()).doesNotContain("Tập");
            assertThat(origin.entrySlug()).isEqualTo("ban-chieu-chinh");
        }

        @Test
        @DisplayName("Tombstone origin IDs are excluded from scoped origin query and active replies preserve origins")
        void shouldExcludeTombstoneOriginsAndBatchDistinctActiveOrigins() {
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root on Ep 1", NOW, EPISODE_1_ENTRY_ID);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);

            Comment tombstoneParent = Comment.createReply(
                    REPLY_1_ID, root, TOMBSTONE_AUTHOR_ID, "Deleted parent", NOW.plusSeconds(2), FEATURE_ENTRY_ID
            );
            Comment activeChild = Comment.createReply(
                    REPLY_2_ID, tombstoneParent, AUTHOR_2_ID, "Nested active reply", NOW.plusSeconds(4), EPISODE_2_ENTRY_ID
            );
            tombstoneParent.delete(NOW.plusSeconds(5));
            CommentReadItem tombstoneItem = CommentReadItem.fromTombstoneReply(tombstoneParent, AUTHOR_1_ID);
            CommentReadItem activeChildItem = CommentReadItem.fromActiveReply(activeChild, null);

            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 2));
            when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 10, false));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(new CommentThreadView(rootItem, List.of(tombstoneItem, activeChildItem))));
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                    .thenReturn(Map.of(
                            AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"),
                            AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null, "author_two")
                    ));
            when(screenDiscussionContract.findPublishedEntryOriginsByTitleIdAndEntryIds(
                    TITLE_ID,
                    Set.of(EPISODE_1_ENTRY_ID, EPISODE_2_ENTRY_ID)
            )).thenReturn(Map.of(
                    EPISODE_1_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_1_ENTRY_ID, "tap-1", "Tập 1"),
                    EPISODE_2_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_2_ENTRY_ID, "tap-2", "Tập 2")
            ));

            ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(TITLE_ID, 0, 10, null, CommentSortMode.FEATURED);

            assertThat(response.threads()).hasSize(1);
            assertThat(response.threads().get(0).root().origin().badgeText()).isEqualTo("Tập 1");
            assertThat(response.threads().get(0).replies().get(0).tombstone()).isTrue();
            assertThat(response.threads().get(0).replies().get(0).origin()).isNull();
            assertThat(response.threads().get(0).replies().get(1).origin().badgeText()).isEqualTo("Tập 2");

            verify(screenDiscussionContract).findPublishedEntryOriginsByTitleIdAndEntryIds(
                    TITLE_ID,
                    Set.of(EPISODE_1_ENTRY_ID, EPISODE_2_ENTRY_ID)
            );
            verify(screenDiscussionContract, never()).findPublishedEntryOriginsByTitleId(any());
        }
    }

    @Nested
    @DisplayName("Opt-In Diagnostic Instrumentation Tests")
    class DiagnosticInstrumentationTests {

        private ScreenTitleDiscussionQueryCoordinator diagnosticCoordinator() {
            return new ScreenTitleDiscussionQueryCoordinator(
                    screenDiscussionContract,
                    getCommentTargetMetricsUseCase,
                    listCommentRootsUseCase,
                    getCommentThreadsByRootIdsUseCase,
                    getCommentThreadUseCase,
                    validateCommentTargetScopeUseCase,
                    userIdentityContract,
                    getBatchReactionSummariesUseCase,
                    true
            );
        }

        @Test
        @DisplayName("Opt-in diagnostics preserve DTO output, invoke zero extra queries, and log no PII or UUIDs")
        void shouldPreserveOutputAndZeroExtraQueriesWhenDiagnosticEnabled() {
            UUID viewerId = UUID.fromString("99999999-9999-9999-9999-999999999999");
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 3));

            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Sensitive root body text", NOW, EPISODE_1_ENTRY_ID);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);

            Comment reply1 = Comment.createReply(REPLY_1_ID, root, AUTHOR_2_ID, "Sensitive reply body text", NOW.plusSeconds(5), EPISODE_2_ENTRY_ID);
            CommentReadItem reply1Item = CommentReadItem.fromActiveReply(reply1, AUTHOR_1_ID);

            Comment tombstoneReply = Comment.createReply(REPLY_2_ID, root, TOMBSTONE_AUTHOR_ID, "Tombstone hidden body", NOW.plusSeconds(10));
            tombstoneReply.delete(NOW.plusSeconds(11));
            CommentReadItem tombstoneReplyItem = CommentReadItem.fromTombstoneReply(tombstoneReply, AUTHOR_1_ID);

            when(listCommentRootsUseCase.execute(target, CommentSortMode.NEWEST, 1, 20))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 1, 20, true));
            when(screenDiscussionContract.findPublishedEntryOriginsByTitleIdAndEntryIds(
                    TITLE_ID,
                    Set.of(EPISODE_1_ENTRY_ID, EPISODE_2_ENTRY_ID)
            )).thenReturn(Map.of(
                    EPISODE_1_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_1_ENTRY_ID, "tap-1", "Tập 1"),
                    EPISODE_2_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_2_ENTRY_ID, "tap-2", "Tập 2")
            ));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(new CommentThreadView(rootItem, List.of(reply1Item, tombstoneReplyItem))));
            // Deliberately omit AUTHOR_2_ID to verify identity fallback also stays identical
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                    .thenReturn(Map.of(
                            AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Secret Display Name", null, "secret_handle")
                    ));
            when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(ROOT_1_ID, REPLY_1_ID), viewerId))
                    .thenReturn(Map.of(
                            ROOT_1_ID, ReactionSummary.of(ReactionTarget.comment(ROOT_1_ID), Map.of(ReactionType.LIKE, 4L), ReactionType.LIKE)
                    ));

            Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(ScreenTitleDiscussionQueryCoordinator.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            coordinatorLogger.addAppender(appender);
            try {
                // Default coordinator (diagnostics disabled)
                ScreenDiscussionFeedResponseDTO defaultResult =
                        coordinator.getDiscussionFeed(TITLE_ID, 1, 20, viewerId, CommentSortMode.NEWEST);
                assertThat(appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.contains("[PERF-DIAGNOSTIC]")))
                        .isEmpty();

                // Opt-in diagnostic coordinator
                ScreenDiscussionFeedResponseDTO diagResult =
                        diagnosticCoordinator().getDiscussionFeed(TITLE_ID, 1, 20, viewerId, CommentSortMode.NEWEST);

                assertThat(diagResult).isEqualTo(defaultResult);
                assertThat(diagResult.threads()).hasSize(1);
                assertThat(diagResult.threads().get(0).root().origin().badgeText()).isEqualTo("Tập 1");
                assertThat(diagResult.threads().get(0).replies().get(0).author().displayName()).isEqualTo(CommentAuthorDTO.DEFAULT_DISPLAY_NAME);
                assertThat(diagResult.threads().get(0).replies().get(1).tombstone()).isTrue();
                assertThat(diagResult.hasNext()).isTrue();

                // Exactly 1 invocation per diagnostic run (= 2 total across both runs), zero extra queries
                verify(screenDiscussionContract, times(2)).isPublishedTitle(TITLE_ID);
                verify(getCommentTargetMetricsUseCase, times(2)).execute(target);
                verify(listCommentRootsUseCase, times(2)).execute(target, CommentSortMode.NEWEST, 1, 20);
                verify(screenDiscussionContract, times(2)).findPublishedEntryOriginsByTitleIdAndEntryIds(
                        TITLE_ID,
                        Set.of(EPISODE_1_ENTRY_ID, EPISODE_2_ENTRY_ID)
                );
                verify(getCommentThreadsByRootIdsUseCase, times(2)).execute(target, List.of(ROOT_1_ID));
                verify(userIdentityContract, times(2)).findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID));
                verify(getBatchReactionSummariesUseCase, times(2))
                        .execute(ReactionTargetType.COMMENT, List.of(ROOT_1_ID, REPLY_1_ID), viewerId);
                verifyNoMoreInteractions(
                        screenDiscussionContract,
                        getCommentTargetMetricsUseCase,
                        listCommentRootsUseCase,
                        getCommentThreadsByRootIdsUseCase,
                        userIdentityContract,
                        getBatchReactionSummariesUseCase
                );

                List<String> diagLogs = appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.contains("[PERF-DIAGNOSTIC]"))
                        .toList();
                assertThat(diagLogs).hasSize(1);
                String diagLog = diagLogs.get(0);
                assertThat(diagLog)
                        .contains("/api/screen/titles/{titleId}/comments feed:")
                        .contains("publish_check=")
                        .contains("target_metrics=")
                        .contains("root_slice=")
                        .contains("entry_origins=")
                        .contains("threads_batch=")
                        .contains("identity_batch=")
                        .contains("reactions_batch=")
                        .contains("dto_compose=")
                        .contains("sort=NEWEST")
                        .contains("page=1")
                        .contains("size=20")
                        .contains("has_next=true")
                        .contains("auth=true")
                        .contains("empty=false")
                        .doesNotContain(TITLE_ID.toString())
                        .doesNotContain(ROOT_1_ID.toString())
                        .doesNotContain(REPLY_1_ID.toString())
                        .doesNotContain(REPLY_2_ID.toString())
                        .doesNotContain(AUTHOR_1_ID.toString())
                        .doesNotContain(AUTHOR_2_ID.toString())
                        .doesNotContain(TOMBSTONE_AUTHOR_ID.toString())
                        .doesNotContain(viewerId.toString())
                        .doesNotContain(EPISODE_1_ENTRY_ID.toString())
                        .doesNotContain(EPISODE_2_ENTRY_ID.toString())
                        .doesNotContain("Secret Display Name")
                        .doesNotContain("secret_handle")
                        .doesNotContain("Sensitive root body")
                        .doesNotContain("Sensitive reply body")
                        .doesNotContain("Tombstone hidden body");
            } finally {
                coordinatorLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("Opt-in diagnostics cover empty feed, ineligible title, and reaction failure degradation safely")
        void shouldLogDiagnosticsForEmptyIneligibleAndReactionFailureWithoutSensitiveData() {
            ScreenTitleDiscussionQueryCoordinator diagCoordinator = diagnosticCoordinator();
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);

            Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(ScreenTitleDiscussionQueryCoordinator.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            coordinatorLogger.addAppender(appender);
            try {
                // 1. Ineligible / unpublished title
                when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(false);
                assertThatThrownBy(() -> diagCoordinator.getDiscussionFeed(TITLE_ID, 0, 20, null, CommentSortMode.FEATURED))
                        .isInstanceOf(CommentTargetNotEligibleException.class);

                // 2. Empty feed
                when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
                when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(CommentTargetMetrics.EMPTY);
                when(listCommentRootsUseCase.execute(target, CommentSortMode.NEWEST, 0, 20))
                        .thenReturn(new CommentReadSlice(List.of(), 0, 20, false));
                ScreenDiscussionFeedResponseDTO emptyRes =
                        diagCoordinator.getDiscussionFeed(TITLE_ID, 0, 20, null, CommentSortMode.NEWEST);
                assertThat(emptyRes.threads()).isEmpty();

                // 3. Reaction failure degradation
                Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Body that fails reaction", NOW);
                CommentReadItem rootItem = CommentReadItem.fromRoot(root);
                when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));
                when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 20))
                        .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 20, false));
                when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                        .thenReturn(List.of(new CommentThreadView(rootItem, List.of())));
                when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                        .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "User One", null, "user_one")));
                when(getBatchReactionSummariesUseCase.execute(any(), any(), any()))
                        .thenThrow(new RuntimeException("Simulated reaction failure"));

                ScreenDiscussionFeedResponseDTO fallbackRes =
                        diagCoordinator.getDiscussionFeed(TITLE_ID, 0, 20, null, CommentSortMode.FEATURED);
                assertThat(fallbackRes.threads()).hasSize(1);
                assertThat(fallbackRes.threads().get(0).root().reactionSummary()).isNull();

                List<String> diagLogs = appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.contains("[PERF-DIAGNOSTIC]"))
                        .toList();
                assertThat(diagLogs).hasSize(3);
                assertThat(diagLogs.get(0)).contains("eligible=false").doesNotContain(TITLE_ID.toString());
                assertThat(diagLogs.get(1)).contains("empty=true").contains("auth=false").doesNotContain(TITLE_ID.toString());
                assertThat(diagLogs.get(2))
                        .contains("failed=true")
                        .contains("empty=false")
                        .doesNotContain(TITLE_ID.toString())
                        .doesNotContain(AUTHOR_1_ID.toString())
                        .doesNotContain("User One")
                        .doesNotContain("Body that fails reaction");
            } finally {
                coordinatorLogger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Nested
    @DisplayName("Phase A/B Isolation and Reaction Failure Handling Tests")
    class PhaseIsolationAndReactionFailureTests {

        @Test
        @DisplayName("Primary coordinator constructor executes Phase A core read strictly before Phase B reactions")
        void shouldInvokeCoreReadStrictlyBeforeReactionBatch() {
            ScreenTitleDiscussionCoreReadService mockCoreReadService = Mockito.mock(ScreenTitleDiscussionCoreReadService.class);
            ScreenTitleDiscussionQueryCoordinator primaryCoordinator = new ScreenTitleDiscussionQueryCoordinator(
                    screenDiscussionContract,
                    mockCoreReadService,
                    getCommentThreadUseCase,
                    validateCommentTargetScopeUseCase,
                    userIdentityContract,
                    getBatchReactionSummariesUseCase,
                    false
            );
            UUID viewerId = UUID.fromString("99999999-9999-9999-9999-999999999999");
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root Body", NOW, EPISODE_1_ENTRY_ID);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);
            var coreResult = new ScreenTitleDiscussionCoreReadService.CoreFeedReadResult(
                    1,
                    1,
                    0,
                    20,
                    false,
                    1,
                    Map.of(EPISODE_1_ENTRY_ID, new ScreenEntryOriginDTO(EPISODE_1_ENTRY_ID, "tap-1", "Tập 1")),
                    List.of(ROOT_1_ID),
                    Map.of(ROOT_1_ID, new CommentThreadView(rootItem, List.of())),
                    Set.of(AUTHOR_1_ID),
                    List.of(ROOT_1_ID),
                    Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")),
                    ScreenTitleDiscussionCoreReadService.CoreStageTimings.ZERO
            );
            when(mockCoreReadService.readFeedCore(TITLE_ID, 0, 20, CommentSortMode.NEWEST, false))
                    .thenReturn(coreResult);
            when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(ROOT_1_ID), viewerId))
                    .thenReturn(Map.of(
                            ROOT_1_ID, ReactionSummary.of(ReactionTarget.comment(ROOT_1_ID), Map.of(ReactionType.LIKE, 3L), ReactionType.LIKE)
                    ));

            ScreenDiscussionFeedResponseDTO response = primaryCoordinator.getDiscussionFeed(
                    TITLE_ID, 0, 20, viewerId, CommentSortMode.NEWEST
            );

            assertThat(response.threads()).hasSize(1);
            assertThat(response.threads().get(0).root().origin().badgeText()).isEqualTo("Tập 1");
            assertThat(response.threads().get(0).root().reactionSummary().totalCount()).isEqualTo(3L);
            assertThat(response.threads().get(0).root().reactionSummary().currentUserReaction()).isEqualTo("LIKE");

            InOrder inOrder = Mockito.inOrder(mockCoreReadService, getBatchReactionSummariesUseCase);
            inOrder.verify(mockCoreReadService).readFeedCore(TITLE_ID, 0, 20, CommentSortMode.NEWEST, false);
            inOrder.verify(getBatchReactionSummariesUseCase)
                    .execute(ReactionTargetType.COMMENT, List.of(ROOT_1_ID), viewerId);
        }

        @Test
        @DisplayName("Empty reaction map degrades cleanly without failure logs")
        void shouldPreserveFeedWhenReactionResultIsEmpty() {
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));
            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root Body", NOW);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);
            when(listCommentRootsUseCase.execute(target, CommentSortMode.NEWEST, 0, 20))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 20, false));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(new CommentThreadView(rootItem, List.of())));
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                    .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));
            when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(ROOT_1_ID), null))
                    .thenReturn(Map.of());

            Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(ScreenTitleDiscussionQueryCoordinator.class);
            Logger reactionLogger = (Logger) LoggerFactory.getLogger(CommentReactionEnrichmentService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            coordinatorLogger.addAppender(appender);
            reactionLogger.addAppender(appender);
            try {
                ScreenDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(
                        TITLE_ID, 0, 20, null, CommentSortMode.NEWEST
                );
                assertThat(response.threads()).hasSize(1);
                assertThat(response.threads().get(0).root().reactionSummary()).isNull();
                assertThat(appender.list).isEmpty();
            } finally {
                coordinatorLogger.detachAppender(appender);
                reactionLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("Reaction persistence exception degrades best-effort with sanitized warning without leaking UUID or SQL params")
        void shouldEmitSanitizedWarningWhenReactionPersistenceExceptionOccurs() {
            UUID viewerId = UUID.fromString("99999999-9999-9999-9999-999999999999");
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));
            Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root Body", NOW);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);
            when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 20))
                    .thenReturn(new CommentReadSlice(List.of(rootItem), 0, 20, false));
            when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                    .thenReturn(List.of(new CommentThreadView(rootItem, List.of())));
            when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                    .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));
            when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(ROOT_1_ID), viewerId))
                    .thenThrow(new QueryTimeoutException("SQL bind secret_token_xyz failed for " + viewerId));

            ScreenTitleDiscussionQueryCoordinator diagCoordinator = new ScreenTitleDiscussionQueryCoordinator(
                    screenDiscussionContract,
                    getCommentTargetMetricsUseCase,
                    listCommentRootsUseCase,
                    getCommentThreadsByRootIdsUseCase,
                    getCommentThreadUseCase,
                    validateCommentTargetScopeUseCase,
                    userIdentityContract,
                    getBatchReactionSummariesUseCase,
                    true
            );

            Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(ScreenTitleDiscussionQueryCoordinator.class);
            Logger reactionLogger = (Logger) LoggerFactory.getLogger(CommentReactionEnrichmentService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            coordinatorLogger.addAppender(appender);
            reactionLogger.addAppender(appender);
            try {
                ScreenDiscussionFeedResponseDTO response = diagCoordinator.getDiscussionFeed(
                        TITLE_ID, 0, 20, viewerId, CommentSortMode.FEATURED
                );

                assertThat(response.threads()).hasSize(1);
                assertThat(response.threads().get(0).root().body()).isEqualTo("Root Body");
                assertThat(response.threads().get(0).root().reactionSummary()).isNull();

                List<ILoggingEvent> warnings = appender.list.stream()
                        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                        .toList();
                assertThat(warnings).hasSize(1);
                String warnMsg = warnings.get(0).getFormattedMessage();
                assertThat(warnMsg)
                        .contains("QueryTimeoutException")
                        .contains("activeCommentCount=1")
                        .contains("authenticatedViewer=true")
                        .doesNotContain(TITLE_ID.toString())
                        .doesNotContain(viewerId.toString())
                        .doesNotContain("secret_token_xyz");
                assertThat(warnings.get(0).getThrowableProxy()).isNull();

                List<String> diagLogs = appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.contains("[PERF-DIAGNOSTIC]"))
                        .toList();
                assertThat(diagLogs).hasSize(1);
                assertThat(diagLogs.get(0)).contains("failed=true");
            } finally {
                coordinatorLogger.detachAppender(appender);
                reactionLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("Core persistence exception propagates and never calls reaction lookup")
        void shouldPropagateCorePersistenceExceptionWithoutCallingReactions() {
            CommentTarget target = CommentTarget.screenTitle(TITLE_ID);
            when(screenDiscussionContract.isPublishedTitle(TITLE_ID)).thenReturn(true);
            when(getCommentTargetMetricsUseCase.execute(target))
                    .thenThrow(new QueryTimeoutException("Core metrics DB failure"));

            assertThatThrownBy(() -> coordinator.getDiscussionFeed(TITLE_ID, 0, 20, null, CommentSortMode.NEWEST))
                    .isInstanceOf(QueryTimeoutException.class)
                    .hasMessageContaining("Core metrics DB failure");

            verify(getBatchReactionSummariesUseCase, never()).execute(any(), any(), any());
        }
    }
}
