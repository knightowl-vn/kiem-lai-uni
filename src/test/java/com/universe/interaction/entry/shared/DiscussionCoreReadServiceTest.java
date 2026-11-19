package com.universe.interaction.entry.shared;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreThreadedFeedResult;
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
    private UserIdentityContract userIdentityContract;

    private DiscussionCoreReadService service;

    @BeforeEach
    void setUp() {
        service = new DiscussionCoreReadService(
                getCommentTargetMetricsUseCase,
                listCommentRootsUseCase,
                getCommentThreadsByRootIdsUseCase,
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
}
