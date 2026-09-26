package com.universe.interaction.entry.wiki;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
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
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.interaction.entry.wiki.dto.WikiDiscussionFeedResponseDTO;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
@DisplayName("WikiArticleDiscussionQueryCoordinator Unit Tests")
class WikiArticleDiscussionQueryCoordinatorTest {

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

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

    private WikiArticleDiscussionQueryCoordinator coordinator;

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
        coordinator = new WikiArticleDiscussionQueryCoordinator(
                wikiArticleQueryPort,
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
    @DisplayName("Unpublished article gates before Interaction query on feed")
    void shouldGateUnpublishedArticleBeforeInteractionQueryOnFeed() {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(ARTICLE_ID, 0, 20))
                .isInstanceOf(PublishedWikiArticleNotFoundException.class);

        verify(getCommentTargetMetricsUseCase, never()).execute(any());
        verify(listCommentRootsUseCase, never()).execute(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Unpublished article gates before Interaction query on single thread")
    void shouldGateUnpublishedArticleBeforeInteractionQueryOnThread() {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> coordinator.getCommentThread(ARTICLE_ID, ROOT_1_ID, null))
                .isInstanceOf(PublishedWikiArticleNotFoundException.class);

        verify(validateCommentTargetScopeUseCase, never()).executeRoot(any(), any());
        verify(getCommentThreadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Empty feed returns empty thread list and zero metrics without batch thread query")
    void shouldReturnEmptyFeedWhenNoComments() {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(CommentTargetMetrics.EMPTY);
        when(listCommentRootsUseCase.execute(target, 0, 20))
                .thenReturn(new CommentReadSlice(List.of(), 0, 20, false));

        WikiDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(ARTICLE_ID, 0, 20);

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
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(2, 5));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        Comment root2 = Comment.createRoot(ROOT_2_ID, target, AUTHOR_2_ID, "Root 2 Body", NOW.plusSeconds(10));
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentReadItem root2Item = CommentReadItem.fromRoot(root2);

        // Slice order: root2 then root1
        when(listCommentRootsUseCase.execute(target, 0, 2))
                .thenReturn(new CommentReadSlice(List.of(root2Item, root1Item), 0, 2, false));

        CommentThreadView thread2View = new CommentThreadView(root2Item, List.of());
        CommentThreadView thread1View = new CommentThreadView(root1Item, List.of());

        // Batch returns thread views
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_2_ID, ROOT_1_ID)))
                .thenReturn(List.of(thread1View, thread2View));

        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null)
                ));

        WikiDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(ARTICLE_ID, 0, 2, null);

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
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 2));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        when(listCommentRootsUseCase.execute(target, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(root1Item), 0, 10, false));

        // Reply 1 is active
        Comment reply1 = Comment.createReply(REPLY_1_ID, root1, AUTHOR_2_ID, "Active reply", NOW.plusSeconds(5));
        CommentReadItem reply1Item = CommentReadItem.fromActiveReply(reply1, AUTHOR_1_ID);

        // Reply 2 is tombstone (deleted)
        Comment reply2 = Comment.createReply(REPLY_2_ID, root1, TOMBSTONE_AUTHOR_ID, "To delete", NOW.plusSeconds(10));
        reply2.delete(NOW.plusSeconds(15));
        CommentReadItem reply2Item = CommentReadItem.fromTombstoneReply(reply2, null);

        CommentThreadView thread1View = new CommentThreadView(root1Item, List.of(reply1Item, reply2Item));
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                .thenReturn(List.of(thread1View));

        // Batch profile lookup must contain ONLY active author IDs (AUTHOR_1_ID and AUTHOR_2_ID), NEVER TOMBSTONE_AUTHOR_ID
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID, AUTHOR_2_ID)))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", "/a1.png"),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", "/a2.png")
                ));

        WikiDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(ARTICLE_ID, 0, 10, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<UUID>> profileCaptor = ArgumentCaptor.forClass(Set.class);
        verify(userIdentityContract).findPublicProfilesByIds(profileCaptor.capture());
        assertThat(profileCaptor.getValue()).containsExactlyInAnyOrder(AUTHOR_1_ID, AUTHOR_2_ID);
        assertThat(profileCaptor.getValue()).doesNotContain(TOMBSTONE_AUTHOR_ID);

        CommentThreadResponseDTO thread = response.threads().get(0);
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
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(2, 2));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1", NOW);
        Comment root2 = Comment.createRoot(ROOT_2_ID, target, AUTHOR_2_ID, "Root 2", NOW.plusSeconds(5));
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentReadItem root2Item = CommentReadItem.fromRoot(root2);

        when(listCommentRootsUseCase.execute(target, 0, 2))
                .thenReturn(new CommentReadSlice(List.of(root1Item, root2Item), 0, 2, false));

        // Authoritative batch returns only root1 (root2 was concurrently deleted)
        CommentThreadView thread1View = new CommentThreadView(root1Item, List.of());
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID, ROOT_2_ID)))
                .thenReturn(List.of(thread1View));

        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null)));

        WikiDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(ARTICLE_ID, 0, 2, null);

        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().id()).isEqualTo(ROOT_1_ID);
    }

    @Test
    @DisplayName("Single-thread cross-target validation happens before thread retrieval")
    void shouldValidateTargetScopeBeforeThreadRetrieval() {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        doThrow(new CommentNotFoundException("Target scope mismatch"))
                .when(validateCommentTargetScopeUseCase).executeRoot(ROOT_1_ID, target);

        assertThatThrownBy(() -> coordinator.getCommentThread(ARTICLE_ID, ROOT_1_ID, null))
                .isInstanceOf(CommentNotFoundException.class);

        verify(getCommentThreadUseCase, never()).execute(any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }

    @Test
    @DisplayName("Feed queries batch reactions for active roots and active replies, omitting tombstones")
    void shouldQueryBatchReactionsForActiveCommentsInFeed() {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 2));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        when(listCommentRootsUseCase.execute(target, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(root1Item), 0, 10, false));

        Comment reply1 = Comment.createReply(REPLY_1_ID, root1, AUTHOR_2_ID, "Active reply", NOW.plusSeconds(5));
        CommentReadItem reply1Item = CommentReadItem.fromActiveReply(reply1, AUTHOR_1_ID);

        Comment reply2 = Comment.createReply(REPLY_2_ID, root1, TOMBSTONE_AUTHOR_ID, "Deleted reply", NOW.plusSeconds(10));
        reply2.delete(NOW.plusSeconds(15));
        CommentReadItem reply2Item = CommentReadItem.fromTombstoneReply(reply2, null);

        CommentThreadView thread1View = new CommentThreadView(root1Item, List.of(reply1Item, reply2Item));
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                .thenReturn(List.of(thread1View));

        when(userIdentityContract.findPublicProfilesByIds(any()))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null)
                ));

        ReactionSummary rootSummary = ReactionSummary.of(
                com.universe.interaction.domain.reaction.ReactionTarget.comment(ROOT_1_ID),
                Map.of(ReactionType.LIKE, 2L, ReactionType.LOVE, 1L),
                ReactionType.LIKE
        );
        ReactionSummary reply1Summary = ReactionSummary.of(
                com.universe.interaction.domain.reaction.ReactionTarget.comment(REPLY_1_ID),
                Map.of(ReactionType.FIRE, 1L),
                null
        );

        when(getBatchReactionSummariesUseCase.execute(eq(ReactionTargetType.COMMENT), any(), eq(AUTHOR_1_ID)))
                .thenReturn(Map.of(
                        ROOT_1_ID, rootSummary,
                        REPLY_1_ID, reply1Summary
                ));

        WikiDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(ARTICLE_ID, 0, 10, AUTHOR_1_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> activeCommentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(getBatchReactionSummariesUseCase).execute(eq(ReactionTargetType.COMMENT), activeCommentsCaptor.capture(), eq(AUTHOR_1_ID));
        assertThat(activeCommentsCaptor.getValue()).containsExactlyInAnyOrder(ROOT_1_ID, REPLY_1_ID);
        assertThat(activeCommentsCaptor.getValue()).doesNotContain(REPLY_2_ID);

        CommentThreadResponseDTO thread = response.threads().get(0);
        assertThat(thread.root().reactionSummary()).isNotNull();
        assertThat(thread.root().reactionSummary().totalCount()).isEqualTo(3);
        assertThat(thread.root().reactionSummary().currentUserReaction()).isEqualTo("LIKE");

        assertThat(thread.replies().get(0).reactionSummary()).isNotNull();
        assertThat(thread.replies().get(0).reactionSummary().totalCount()).isEqualTo(1);
        assertThat(thread.replies().get(0).reactionSummary().currentUserReaction()).isNull();

        assertThat(thread.replies().get(1).reactionSummary()).isNull();
    }

    @Test
    @DisplayName("Single thread queries batch reactions for active root and active replies")
    void shouldQueryBatchReactionsForSingleThread() {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        Comment reply1 = Comment.createReply(REPLY_1_ID, root1, AUTHOR_2_ID, "Active reply", NOW.plusSeconds(5));
        CommentReadItem reply1Item = CommentReadItem.fromActiveReply(reply1, AUTHOR_1_ID);

        CommentThreadView threadView = new CommentThreadView(root1Item, List.of(reply1Item));
        when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(threadView);

        when(userIdentityContract.findPublicProfilesByIds(any()))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "Author Two", null)
                ));

        ReactionSummary rootSummary = ReactionSummary.of(
                com.universe.interaction.domain.reaction.ReactionTarget.comment(ROOT_1_ID),
                Map.of(ReactionType.LIKE, 1L),
                null
        );
        when(getBatchReactionSummariesUseCase.execute(eq(ReactionTargetType.COMMENT), any(), any()))
                .thenReturn(Map.of(ROOT_1_ID, rootSummary));

        CommentThreadResponseDTO response = coordinator.getCommentThread(ARTICLE_ID, ROOT_1_ID, null);

        assertThat(response.root().reactionSummary()).isNotNull();
        assertThat(response.root().reactionSummary().totalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reaction query failure degrades gracefully without failing feed load")
    void shouldDegradeGracefullyWhenReactionQueryFails() {
        when(wikiArticleQueryPort.isPublished(ARTICLE_ID)).thenReturn(true);
        CommentTarget target = CommentTarget.wikiArticle(ARTICLE_ID);

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(1, 1));

        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1 Body", NOW);
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        when(listCommentRootsUseCase.execute(target, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(root1Item), 0, 10, false));

        CommentThreadView thread1View = new CommentThreadView(root1Item, List.of());
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                .thenReturn(List.of(thread1View));

        when(userIdentityContract.findPublicProfilesByIds(any()))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null)));

        when(getBatchReactionSummariesUseCase.execute(any(), any(), any()))
                .thenThrow(new RuntimeException("Transient DB timeout"));

        WikiDiscussionFeedResponseDTO response = coordinator.getDiscussionFeed(ARTICLE_ID, 0, 10, null);

        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().reactionSummary()).isNull();
    }
}
