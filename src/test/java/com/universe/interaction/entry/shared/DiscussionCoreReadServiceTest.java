package com.universe.interaction.entry.shared;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreKnownRootsResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreRootsOnlyFeedResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreSingleThreadResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreThreadedFeedResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.KnownRootsSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscussionCoreReadService Unit Tests")
class DiscussionCoreReadServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AUTHOR_1_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;

    @Mock
    private ListCommentRootsUseCase listCommentRootsUseCase;

    @Mock
    private GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;

    @Mock
    private ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;

    @Mock
    private GetCommentThreadUseCase getCommentThreadUseCase;

    @Mock
    private CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    private DiscussionCoreReadService service;

    @BeforeEach
    void setUp() {
        service = new DiscussionCoreReadService(
                getCommentTargetMetricsUseCase,
                listCommentRootsUseCase,
                getCommentThreadsByRootIdsUseCase,
                validateCommentTargetScopeUseCase,
                getCommentThreadUseCase,
                countVisibleActiveRepliesByRootIdsUseCase,
                userIdentityContract
        );
    }

    @Test
    @DisplayName("Eligibility gate fails closed before any core queries run")
    void shouldFailClosedOnEligibilityGate() {
        CommentTarget target = CommentTarget.screenTitle(TARGET_ID);

        assertThatThrownBy(() -> service.readThreadedFeed(
                target,
                0,
                20,
                CommentSortMode.NEWEST,
                true,
                false,
                () -> {
                    throw new CommentTargetNotEligibleException(target);
                }
        )).isInstanceOf(CommentTargetNotEligibleException.class);

        verify(getCommentTargetMetricsUseCase, never()).execute(any());
        verify(listCommentRootsUseCase, never()).execute(any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("Empty root slice skips attachment loader, batch thread read, and author lookup")
    void shouldSkipAttachmentsAndBatchLookupsOnEmptySlice() {
        CommentTarget target = CommentTarget.wikiArticle(TARGET_ID);
        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(CommentTargetMetrics.EMPTY);
        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 20))
                .thenReturn(new CommentReadSlice(List.of(), 0, 20, false));
        AtomicBoolean attachmentCalled = new AtomicBoolean(false);

        CoreThreadedFeedResult<String> result = service.readThreadedFeed(
                target,
                0,
                20,
                null,
                true,
                true,
                () -> {},
                () -> {
                    attachmentCalled.set(true);
                    return "should-not-run";
                }
        );

        assertThat(attachmentCalled.get()).isFalse();
        assertThat(result.contextAttachment()).isNull();
        assertThat(result.survivingRootIds()).isEmpty();
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }

    @Test
    @DisplayName("Supports optional metrics skipping, preserves slice order, and returns defensive immutable collections")
    void shouldPreserveSliceOrderingAndImmutableResultSnapshots() {
        CommentTarget target = CommentTarget.novelChapter(TARGET_ID);
        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1", NOW);
        Comment root2 = Comment.createRoot(ROOT_2_ID, target, AUTHOR_1_ID, "Root 2", NOW.plusSeconds(1));
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentReadItem root2Item = CommentReadItem.fromRoot(root2);

        when(listCommentRootsUseCase.execute(target, CommentSortMode.NEWEST, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(root2Item, root1Item), 0, 10, true));
        // Concurrent deletion removes root1 from authoritative batch
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_2_ID, ROOT_1_ID)))
                .thenReturn(List.of(new CommentThreadView(root2Item, List.of())));
        Map<UUID, UserPublicProfileDTO> mutableProfiles = new HashMap<>();
        mutableProfiles.put(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID))).thenReturn(mutableProfiles);

        CoreThreadedFeedResult<String> result = service.readThreadedFeed(
                target,
                0,
                10,
                CommentSortMode.NEWEST,
                false,
                false,
                () -> {},
                () -> "attached-meta"
        );

        verify(getCommentTargetMetricsUseCase, never()).execute(any());
        assertThat(result.threadCount()).isZero();
        assertThat(result.commentCount()).isZero();
        assertThat(result.hasNext()).isTrue();
        assertThat(result.rawRootCount()).isEqualTo(2);
        assertThat(result.contextAttachment()).isEqualTo("attached-meta");
        assertThat(result.survivingRootIds()).containsExactly(ROOT_2_ID);

        // Mutating backing map returned from Identity must not affect snapshot
        mutableProfiles.clear();
        assertThat(result.authorsMap()).containsKey(AUTHOR_1_ID);
        assertThatThrownBy(() -> result.authorsMap().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.threadViewsByRootId().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Single-thread core enforces target scope, suppresses tombstone authors, and returns immutable snapshot")
    void shouldReadSingleThreadCoreSafely() {
        CommentTarget target = CommentTarget.wikiArticle(TARGET_ID);
        UUID tombstoneReplyId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID tombstoneAuthorId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Comment root = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1", NOW);
        Comment deletedReply = Comment.createReply(tombstoneReplyId, root, tombstoneAuthorId, "Gone", NOW.plusSeconds(1));
        deletedReply.delete(NOW.plusSeconds(2));

        CommentThreadView view = new CommentThreadView(
                CommentReadItem.fromRoot(root),
                List.of(CommentReadItem.fromTombstoneReply(deletedReply, AUTHOR_1_ID))
        );
        when(getCommentThreadUseCase.execute(ROOT_1_ID)).thenReturn(view);
        Map<UUID, UserPublicProfileDTO> mutableProfiles = new HashMap<>();
        mutableProfiles.put(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID))).thenReturn(mutableProfiles);

        CoreSingleThreadResult<String> single = service.readSingleThreadCore(
                target,
                ROOT_1_ID,
                () -> {},
                () -> "thread-attachment"
        );

        verify(validateCommentTargetScopeUseCase).executeRoot(ROOT_1_ID, target);
        assertThat(single.participants().activeCommentIds()).containsExactly(ROOT_1_ID);
        assertThat(single.participants().authorUserIds()).containsExactly(AUTHOR_1_ID).doesNotContain(tombstoneAuthorId);
        assertThat(single.contextAttachment()).isEqualTo("thread-attachment");
        mutableProfiles.clear();
        assertThat(single.authorsMap()).containsKey(AUTHOR_1_ID);
        assertThatThrownBy(() -> single.authorsMap().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Single-thread core stops before thread retrieval when scope validation fails")
    void shouldStopBeforeThreadRetrievalOnScopeMismatch() {
        CommentTarget target = CommentTarget.wikiArticle(TARGET_ID);
        doThrow(new CommentNotFoundException("Mismatch"))
                .when(validateCommentTargetScopeUseCase).executeRoot(ROOT_1_ID, target);

        assertThatThrownBy(() -> service.readSingleThreadCore(target, ROOT_1_ID, () -> {}))
                .isInstanceOf(CommentNotFoundException.class);

        verify(getCommentThreadUseCase, never()).execute(any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }

    @Test
    @DisplayName("Surviving-roots attachment loader runs only for surviving roots after concurrent delete filtering")
    void shouldLoadPostThreadsAttachmentOnlyForSurvivingRoots() {
        CommentTarget target = CommentTarget.novelChapter(TARGET_ID);
        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1", NOW);
        Comment root2 = Comment.createRoot(ROOT_2_ID, target, AUTHOR_1_ID, "Root 2", NOW.plusSeconds(1));
        CommentReadItem root1Item = CommentReadItem.fromRoot(root1);
        CommentReadItem root2Item = CommentReadItem.fromRoot(root2);

        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 20))
                .thenReturn(new CommentReadSlice(List.of(root1Item, root2Item), 0, 20, false));
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID, ROOT_2_ID)))
                .thenReturn(List.of(new CommentThreadView(root1Item, List.of())));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID)))
                .thenReturn(Map.of(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one")));

        AtomicBoolean receivedExpectedSurvivingRoots = new AtomicBoolean(false);
        CoreThreadedFeedResult<String> result = service.readThreadedFeedForSurvivingRoots(
                target,
                0,
                20,
                CommentSortMode.FEATURED,
                false,
                true,
                () -> {},
                survivingRootIds -> {
                    receivedExpectedSurvivingRoots.set(survivingRootIds.equals(List.of(ROOT_1_ID)));
                    return "anchors-for-" + survivingRootIds.size();
                }
        );

        assertThat(receivedExpectedSurvivingRoots.get()).isTrue();
        assertThat(result.contextAttachment()).isEqualTo("anchors-for-1");
        assertThat(result.survivingRootIds()).containsExactly(ROOT_1_ID);

        // When all roots disappear concurrently, survivingRootsAttachmentLoader is not called
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID, ROOT_2_ID)))
                .thenReturn(List.of());
        AtomicBoolean calledOnAllDeleted = new AtomicBoolean(false);
        CoreThreadedFeedResult<String> emptyResult = service.readThreadedFeedForSurvivingRoots(
                target,
                0,
                20,
                CommentSortMode.FEATURED,
                false,
                false,
                () -> {},
                ids -> {
                    calledOnAllDeleted.set(true);
                    return "should-not-run";
                }
        );
        assertThat(calledOnAllDeleted.get()).isFalse();
        assertThat(emptyResult.contextAttachment()).isNull();
        assertThat(emptyResult.survivingRootIds()).isEmpty();
    }

    @Test
    @DisplayName("Known-roots core skips threads and identity when rootIds are empty, and returns immutable snapshots when populated")
    void shouldReadKnownRootsCoreSafely() {
        CommentTarget target = CommentTarget.novelChapter(TARGET_ID);

        // 1. Empty root selection preserves attachment without calling Interaction/Identity
        CoreKnownRootsResult<String> emptyResult = service.readKnownRootsCore(
                target,
                () -> {},
                () -> new KnownRootsSelection<>(List.of(), "empty-block-meta")
        );
        assertThat(emptyResult.contextAttachment()).isEqualTo("empty-block-meta");
        assertThat(emptyResult.threadViews()).isEmpty();
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());

        // 2. Eligibility gate fails closed before rootsResolver
        AtomicBoolean resolverCalled = new AtomicBoolean(false);
        assertThatThrownBy(() -> service.readKnownRootsCore(
                target,
                () -> {
                    throw new CommentTargetNotEligibleException(target);
                },
                () -> {
                    resolverCalled.set(true);
                    return new KnownRootsSelection<>(List.of(ROOT_1_ID), "unreachable");
                }
        )).isInstanceOf(CommentTargetNotEligibleException.class);
        assertThat(resolverCalled.get()).isFalse();

        // 3. Populated known roots hydrate threads and authors defensively
        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Known root", NOW);
        CommentThreadView threadView = new CommentThreadView(CommentReadItem.fromRoot(root1), List.of());
        when(getCommentThreadsByRootIdsUseCase.execute(target, List.of(ROOT_1_ID)))
                .thenReturn(List.of(threadView));
        Map<UUID, UserPublicProfileDTO> mutableProfiles = new HashMap<>();
        mutableProfiles.put(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID))).thenReturn(mutableProfiles);

        CoreKnownRootsResult<String> populated = service.readKnownRootsCore(
                target,
                () -> {},
                () -> new KnownRootsSelection<>(List.of(ROOT_1_ID), "populated-block-meta")
        );
        assertThat(populated.contextAttachment()).isEqualTo("populated-block-meta");
        assertThat(populated.threadViews()).containsExactly(threadView);
        assertThat(populated.participants().activeCommentIds()).containsExactly(ROOT_1_ID);
        mutableProfiles.clear();
        assertThat(populated.authorsMap()).containsKey(AUTHOR_1_ID);
        assertThatThrownBy(() -> populated.authorsMap().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> populated.threadViews().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Roots-only feed executes metrics, root slice, batch reply counts, and root authors without hydrating replies")
    void shouldReadRootsOnlyFeedWithoutHydratingThreads() {
        CommentTarget target = CommentTarget.communityPost(TARGET_ID);
        Comment root1 = Comment.createRoot(ROOT_1_ID, target, AUTHOR_1_ID, "Root 1", NOW);
        Comment root2 = Comment.createRoot(ROOT_2_ID, target, AUTHOR_1_ID, "Root 2", NOW.plusSeconds(1));

        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(2L, 7L));
        when(listCommentRootsUseCase.execute(target, CommentSortMode.FEATURED, 0, 10))
                .thenReturn(new CommentReadSlice(List.of(CommentReadItem.fromRoot(root2), CommentReadItem.fromRoot(root1)), 0, 10, true));
        Map<UUID, Long> mutableReplyCounts = new HashMap<>();
        mutableReplyCounts.put(ROOT_2_ID, 4L);
        mutableReplyCounts.put(ROOT_1_ID, 1L);
        when(countVisibleActiveRepliesByRootIdsUseCase.execute(List.of(ROOT_2_ID, ROOT_1_ID)))
                .thenReturn(mutableReplyCounts);
        Map<UUID, UserPublicProfileDTO> mutableProfiles = new HashMap<>();
        mutableProfiles.put(AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", null, "author_one"));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(AUTHOR_1_ID))).thenReturn(mutableProfiles);

        CoreRootsOnlyFeedResult result = service.readRootsOnlyFeed(target, 0, 10, null, () -> {});

        assertThat(result.threadCount()).isEqualTo(2L);
        assertThat(result.commentCount()).isEqualTo(7L);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.roots()).hasSize(2);
        assertThat(result.replyCountsByRootId()).containsEntry(ROOT_2_ID, 4L).containsEntry(ROOT_1_ID, 1L);
        assertThat(result.participants().activeCommentIds()).containsExactly(ROOT_2_ID, ROOT_1_ID);
        assertThat(result.participants().authorUserIds()).containsExactly(AUTHOR_1_ID);

        // Critical invariant: ZERO thread hydration in roots-only feed
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
        verify(getCommentThreadUseCase, never()).execute(any());

        // Returned snapshots remain immutable even if backing maps change
        mutableReplyCounts.clear();
        mutableProfiles.clear();
        assertThat(result.replyCountsByRootId()).hasSize(2);
        assertThat(result.authorsMap()).containsKey(AUTHOR_1_ID);
        assertThatThrownBy(() -> result.replyCountsByRootId().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.authorsMap().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Roots-only feed preserves metrics on empty slice while skipping reply counts and Identity lookup")
    void shouldSkipReplyCountsAndIdentityWhenRootsOnlySliceEmpty() {
        CommentTarget target = CommentTarget.communityPost(TARGET_ID);
        when(getCommentTargetMetricsUseCase.execute(target)).thenReturn(new CommentTargetMetrics(3L, 9L));
        when(listCommentRootsUseCase.execute(target, CommentSortMode.NEWEST, 2, 10))
                .thenReturn(new CommentReadSlice(List.of(), 2, 10, false));

        CoreRootsOnlyFeedResult result = service.readRootsOnlyFeed(target, 2, 10, CommentSortMode.NEWEST, () -> {});

        assertThat(result.roots()).isEmpty();
        assertThat(result.commentCount()).isEqualTo(9L);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
        verify(countVisibleActiveRepliesByRootIdsUseCase, never()).execute(any());
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
    }
}
