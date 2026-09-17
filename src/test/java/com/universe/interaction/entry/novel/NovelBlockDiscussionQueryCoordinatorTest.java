package com.universe.interaction.entry.novel;

import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.novel.application.anchor.ChapterBlockDiscussionAnchorView;
import com.universe.novel.application.anchor.GetChapterBlockDiscussionAnchorsUseCase;
import com.universe.novel.application.exceptions.ReaderBlockNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelBlockDiscussionQueryCoordinator Unit Tests")
class NovelBlockDiscussionQueryCoordinatorTest {

    @Mock
    private GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase;

    @Mock
    private GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;

    private NovelBlockDiscussionQueryCoordinator coordinator;

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BLOCK_KEY = "blk-test";
    private static final String CANONICAL_TEXT = "Paragraph text content.";
    private static final long CONTENT_VERSION = 2L;

    @BeforeEach
    void setUp() {
        coordinator = new NovelBlockDiscussionQueryCoordinator(
                getChapterBlockDiscussionAnchorsUseCase,
                getCommentThreadsByRootIdsUseCase
        );
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when chapterId is null")
    void shouldThrowWhenChapterIdIsNull() {
        assertThatThrownBy(() -> coordinator.getBlockDiscussion((UUID) null, BLOCK_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapterId cannot be null");

        verify(getChapterBlockDiscussionAnchorsUseCase, never()).execute(any(), any());
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when blockKey is null or blank")
    void shouldThrowWhenBlockKeyIsBlank() {
        assertThatThrownBy(() -> coordinator.getBlockDiscussion(CHAPTER_ID, (String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockKey cannot be blank");

        assertThatThrownBy(() -> coordinator.getBlockDiscussion(CHAPTER_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockKey cannot be blank");

        verify(getChapterBlockDiscussionAnchorsUseCase, never()).execute(any(), any());
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should return 0 threadCount and empty threads list without calling Interaction when no anchors resolved")
    void shouldReturnEmptyDiscussionWithoutCallingInteractionWhenNoAnchors() {
        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID,
                CONTENT_VERSION,
                BLOCK_KEY,
                CANONICAL_TEXT,
                Collections.emptyList()
        );

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY))
                .thenReturn(anchorView);

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY);

        assertThat(response).isNotNull();
        assertThat(response.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(response.contentVersion()).isEqualTo(CONTENT_VERSION);
        assertThat(response.blockKey()).isEqualTo(BLOCK_KEY);
        assertThat(response.canonicalText()).isEqualTo(CANONICAL_TEXT);
        assertThat(response.threadCount()).isZero();
        assertThat(response.threads()).isEmpty();

        verify(getChapterBlockDiscussionAnchorsUseCase).execute(CHAPTER_ID, BLOCK_KEY);
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should coordinate Novel anchor view and Interaction threads when anchors exist")
    void shouldCoordinateNovelAndInteractionWhenAnchorsExist() {
        UUID rootId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID authorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID,
                CONTENT_VERSION,
                BLOCK_KEY,
                CANONICAL_TEXT,
                List.of(rootId)
        );

        Comment rootComment = Comment.createRoot(rootId, CommentTarget.novelChapter(CHAPTER_ID), authorId, "Thread root", Instant.now());
        CommentThreadView threadView = new CommentThreadView(CommentReadItem.fromRoot(rootComment), Collections.emptyList());

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY))
                .thenReturn(anchorView);
        when(getCommentThreadsByRootIdsUseCase.execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId)))
                .thenReturn(List.of(threadView));

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY);

        assertThat(response).isNotNull();
        assertThat(response.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(response.contentVersion()).isEqualTo(CONTENT_VERSION);
        assertThat(response.blockKey()).isEqualTo(BLOCK_KEY);
        assertThat(response.canonicalText()).isEqualTo(CANONICAL_TEXT);
        assertThat(response.threadCount()).isEqualTo(1);
        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().id()).isEqualTo(rootId);

        verify(getChapterBlockDiscussionAnchorsUseCase).execute(CHAPTER_ID, BLOCK_KEY);
        verify(getCommentThreadsByRootIdsUseCase).execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId));
    }

    @Test
    @DisplayName("Should propagate ReaderBlockNotFoundException when Novel use case throws it")
    void shouldPropagateReaderBlockNotFoundException() {
        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY))
                .thenThrow(new ReaderBlockNotFoundException(CHAPTER_ID, BLOCK_KEY));

        assertThatThrownBy(() -> coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY))
                .isInstanceOf(ReaderBlockNotFoundException.class);

        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
    }
}
