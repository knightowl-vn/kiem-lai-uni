package com.universe.interaction.entry.novel;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedItemDTO;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedResponseDTO;
import com.universe.novel.application.anchor.ResolveChapterCommentAnchorsByRootIdsUseCase;
import com.universe.novel.application.anchor.ResolvedChapterCommentAnchorView;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelChapterDiscussionFeedQueryCoordinator Unit Tests")
class NovelChapterDiscussionFeedQueryCoordinatorTest {

    @Mock
    private ListCommentRootsUseCase listCommentRootsUseCase;

    @Mock
    private CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase;

    @Mock
    private ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    private NovelChapterDiscussionFeedQueryCoordinator coordinator;

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTHOR_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOT_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROOT_2_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ROOT_3_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID ROOT_4_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static final Instant T1 = Instant.parse("2026-09-18T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-18T01:30:00Z");

    @BeforeEach
    void setUp() {
        coordinator = new NovelChapterDiscussionFeedQueryCoordinator(
                listCommentRootsUseCase,
                countVisibleActiveRepliesByRootIdsUseCase,
                resolveChapterCommentAnchorsByRootIdsUseCase,
                userIdentityContract
        );
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when arguments are invalid")
    void shouldValidateArguments() {
        assertThatThrownBy(() -> coordinator.getDiscussionFeed(null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapterId cannot be null");

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(CHAPTER_ID, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page cannot be negative");

        assertThatThrownBy(() -> coordinator.getDiscussionFeed(CHAPTER_ID, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be greater than zero");
    }

    @Test
    @DisplayName("Should return empty feed response without calling downstream collaborators when slice is empty")
    void shouldReturnEmptyFeedWhenNoRoots() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        when(listCommentRootsUseCase.execute(target, 0, 20))
                .thenReturn(new CommentReadSlice(Collections.emptyList(), 0, 20, false));

        ChapterDiscussionFeedResponseDTO result = coordinator.getDiscussionFeed(CHAPTER_ID, 0, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isFalse();

        verify(countVisibleActiveRepliesByRootIdsUseCase, never()).execute(any());
        verify(userIdentityContract, never()).findPublicProfilesByIds(any());
        verify(resolveChapterCommentAnchorsByRootIdsUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should compose feed with CURRENT, RELOCATED, STALE, and UNANCHORED roots, batched lookups, and correct fields")
    void shouldComposeCompleteDiscussionFeed() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);

        // 4 root comments:
        // Root 1: author 1, edited, CURRENT anchor on blk-1
        // Root 2: author 2, not edited, RELOCATED anchor to blk-2
        // Root 3: author 1, not edited, STALE anchor
        // Root 4: missing author profile (fallback), not edited, UNANCHORED
        CommentReadItem root1 = new CommentReadItem(ROOT_1_ID, AUTHOR_1_ID, null, null, "Root 1 body", false, T1, T2);
        CommentReadItem root2 = new CommentReadItem(ROOT_2_ID, AUTHOR_2_ID, null, null, "Root 2 body", false, T1, T1);
        CommentReadItem root3 = new CommentReadItem(ROOT_3_ID, AUTHOR_1_ID, null, null, "Root 3 body", false, T1, T1);
        CommentReadItem root4 = new CommentReadItem(ROOT_4_ID, UUID.fromString("88888888-8888-8888-8888-888888888888"), null, null, "Root 4 body", false, T1, T1);

        when(listCommentRootsUseCase.execute(target, 0, 20))
                .thenReturn(new CommentReadSlice(List.of(root1, root2, root3, root4), 0, 20, true));

        // Reply counts
        when(countVisibleActiveRepliesByRootIdsUseCase.execute(List.of(ROOT_1_ID, ROOT_2_ID, ROOT_3_ID, ROOT_4_ID)))
                .thenReturn(Map.of(ROOT_1_ID, 3L, ROOT_2_ID, 0L, ROOT_3_ID, 5L));

        // Author profiles (author 1 present, author 2 present, author for root4 missing)
        when(userIdentityContract.findPublicProfilesByIds(any()))
                .thenReturn(Map.of(
                        AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "User One", "https://img/u1.jpg"),
                        AUTHOR_2_ID, new UserPublicProfileDTO(AUTHOR_2_ID, "User Two", null)
                ));

        // Anchor resolutions via Novel application use case
        when(resolveChapterCommentAnchorsByRootIdsUseCase.execute(CHAPTER_ID, List.of(ROOT_1_ID, ROOT_2_ID, ROOT_3_ID, ROOT_4_ID)))
                .thenReturn(List.of(
                        new ResolvedChapterCommentAnchorView(
                                ROOT_1_ID,
                                ChapterCommentAnchorResolutionStatus.CURRENT,
                                "blk-0000000000000001-1",
                                "Current canonical text 1 for block 1"
                        ),
                        new ResolvedChapterCommentAnchorView(
                                ROOT_2_ID,
                                ChapterCommentAnchorResolutionStatus.RELOCATED,
                                "blk-0000000000000004-1",
                                "Relocated text 2 for block 2"
                        ),
                        new ResolvedChapterCommentAnchorView(
                                ROOT_3_ID,
                                ChapterCommentAnchorResolutionStatus.STALE,
                                null,
                                "Original text 3 that has been deleted in new version"
                        )
                ));

        // Execute coordinator
        ChapterDiscussionFeedResponseDTO feed = coordinator.getDiscussionFeed(CHAPTER_ID, 0, 20);

        assertThat(feed).isNotNull();
        assertThat(feed.page()).isEqualTo(0);
        assertThat(feed.size()).isEqualTo(20);
        assertThat(feed.hasNext()).isTrue();
        assertThat(feed.items()).hasSize(4);

        // Verify Root 1: CURRENT
        ChapterDiscussionFeedItemDTO item1 = feed.items().get(0);
        assertThat(item1.rootCommentId()).isEqualTo(ROOT_1_ID);
        assertThat(item1.body()).isEqualTo("Root 1 body");
        assertThat(item1.author().displayName()).isEqualTo("User One");
        assertThat(item1.author().avatarUrl()).isEqualTo("https://img/u1.jpg");
        assertThat(item1.edited()).isTrue();
        assertThat(item1.replyCount()).isEqualTo(3);
        assertThat(item1.anchorStatus()).isEqualTo("CURRENT");
        assertThat(item1.blockKey()).isEqualTo("blk-0000000000000001-1");
        assertThat(item1.passageExcerpt()).isEqualTo("Current canonical text 1 for block 1");

        // Verify Root 2: RELOCATED
        ChapterDiscussionFeedItemDTO item2 = feed.items().get(1);
        assertThat(item2.rootCommentId()).isEqualTo(ROOT_2_ID);
        assertThat(item2.body()).isEqualTo("Root 2 body");
        assertThat(item2.author().displayName()).isEqualTo("User Two");
        assertThat(item2.edited()).isFalse();
        assertThat(item2.replyCount()).isEqualTo(0);
        assertThat(item2.anchorStatus()).isEqualTo("RELOCATED");
        assertThat(item2.blockKey()).isEqualTo("blk-0000000000000004-1");
        assertThat(item2.passageExcerpt()).isEqualTo("Relocated text 2 for block 2");

        // Verify Root 3: STALE -> blockKey MUST BE null, excerpt from original anchor selectedText
        ChapterDiscussionFeedItemDTO item3 = feed.items().get(2);
        assertThat(item3.rootCommentId()).isEqualTo(ROOT_3_ID);
        assertThat(item3.body()).isEqualTo("Root 3 body");
        assertThat(item3.author().displayName()).isEqualTo("User One");
        assertThat(item3.edited()).isFalse();
        assertThat(item3.replyCount()).isEqualTo(5);
        assertThat(item3.anchorStatus()).isEqualTo("STALE");
        assertThat(item3.blockKey()).isNull();
        assertThat(item3.passageExcerpt()).isEqualTo("Original text 3 that has been deleted in new version");

        // Verify Root 4: UNANCHORED -> blockKey null, passageExcerpt null, author fallback
        ChapterDiscussionFeedItemDTO item4 = feed.items().get(3);
        assertThat(item4.rootCommentId()).isEqualTo(ROOT_4_ID);
        assertThat(item4.body()).isEqualTo("Root 4 body");
        assertThat(item4.author().displayName()).isEqualTo("Người dùng");
        assertThat(item4.edited()).isFalse();
        assertThat(item4.replyCount()).isEqualTo(0);
        assertThat(item4.anchorStatus()).isEqualTo("UNANCHORED");
        assertThat(item4.blockKey()).isNull();
        assertThat(item4.passageExcerpt()).isNull();

        // Verify collaborator call counts: exactly one batch call each
        verify(countVisibleActiveRepliesByRootIdsUseCase).execute(List.of(ROOT_1_ID, ROOT_2_ID, ROOT_3_ID, ROOT_4_ID));
        verify(userIdentityContract).findPublicProfilesByIds(any());
        verify(resolveChapterCommentAnchorsByRootIdsUseCase).execute(CHAPTER_ID, List.of(ROOT_1_ID, ROOT_2_ID, ROOT_3_ID, ROOT_4_ID));
    }

    @Test
    @DisplayName("truncatePassage: handles null, short, max length, whitespace, and clean word boundaries strictly <= 140")
    void testTruncatePassage() {
        assertThat(NovelChapterDiscussionFeedQueryCoordinator.truncatePassage(null)).isNull();
        assertThat(NovelChapterDiscussionFeedQueryCoordinator.truncatePassage("")).isEmpty();
        assertThat(NovelChapterDiscussionFeedQueryCoordinator.truncatePassage("   ")).isEmpty();

        String shortText = "Ngắn gọn.";
        assertThat(NovelChapterDiscussionFeedQueryCoordinator.truncatePassage(shortText)).isEqualTo("Ngắn gọn.");

        // Exactly 140 code units
        String exact140 = "A".repeat(140);
        assertThat(NovelChapterDiscussionFeedQueryCoordinator.truncatePassage(exact140)).isEqualTo(exact140);
        assertThat(NovelChapterDiscussionFeedQueryCoordinator.truncatePassage(exact140).length()).isEqualTo(140);

        // Exactly 141 code units -> must truncate with ellipsis and final length <= 140
        String exact141 = "B".repeat(141);
        String truncated141 = NovelChapterDiscussionFeedQueryCoordinator.truncatePassage(exact141);
        assertThat(truncated141).endsWith("...");
        assertThat(truncated141.length()).isLessThanOrEqualTo(140);

        // > 140 code units with space near 140 limit
        String longText = "Đây là một đoạn văn bản rất dài trong tiểu thuyết. Nhân vật chính bước đi trên con đường làng quanh co, ngắm nhìn những hàng cây xanh mướt trải dài vô tận khắp chân trời xa xôi.";
        String truncated = NovelChapterDiscussionFeedQueryCoordinator.truncatePassage(longText);
        assertThat(truncated).endsWith("...");
        assertThat(truncated.length()).isLessThanOrEqualTo(140);
    }
}
