package com.universe.interaction.entry.novel;

import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.novel.application.anchor.CreateChapterCommentBlockAnchorCommand;
import com.universe.novel.application.anchor.CreateChapterCommentBlockAnchorUseCase;
import com.universe.novel.application.exceptions.ChapterCommentAnchorVersionConflictException;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelInlineCommentCreationCoordinator Unit Tests")
class NovelInlineCommentCreationCoordinatorTest {

    private static final UUID ACTOR_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BLOCK_KEY = "blk-0123456789abcdef-1";
    private static final String BODY = "Bình luận tại đoạn văn này.";

    @Mock
    private CreateRootCommentUseCase createRootCommentUseCase;

    @Mock
    private CreateChapterCommentBlockAnchorUseCase createChapterCommentBlockAnchorUseCase;

    private NovelInlineCommentCreationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new NovelInlineCommentCreationCoordinator(
                createRootCommentUseCase,
                createChapterCommentBlockAnchorUseCase
        );
    }

    @Test
    @DisplayName("Uses Interaction root comment ID as Novel anchor rootCommentId and preserves order")
    void shouldCreateRootAndAnchorWithSameIdInOrder() {
        Comment rootComment = Comment.createRoot(
                ROOT_COMMENT_ID,
                CommentTarget.novelChapter(CHAPTER_ID),
                ACTOR_USER_ID,
                BODY,
                Instant.now()
        );
        when(createRootCommentUseCase.execute(any(CreateRootCommentCommand.class))).thenReturn(rootComment);

        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                BLOCK_KEY,
                "Toàn bộ nội dung đoạn văn.",
                Instant.now()
        );
        when(createChapterCommentBlockAnchorUseCase.execute(any(CreateChapterCommentBlockAnchorCommand.class))).thenReturn(anchor);

        UUID resultId = coordinator.createInlineComment(
                ACTOR_USER_ID,
                CHAPTER_ID,
                BODY,
                1L,
                BLOCK_KEY
        );

        assertThat(resultId).isEqualTo(ROOT_COMMENT_ID);

        // Verify call order: root creation first, then anchor creation
        InOrder inOrder = Mockito.inOrder(createRootCommentUseCase, createChapterCommentBlockAnchorUseCase);

        ArgumentCaptor<CreateRootCommentCommand> rootCaptor = ArgumentCaptor.forClass(CreateRootCommentCommand.class);
        inOrder.verify(createRootCommentUseCase).execute(rootCaptor.capture());
        CreateRootCommentCommand rootCommand = rootCaptor.getValue();
        assertThat(rootCommand.actorUserId()).isEqualTo(ACTOR_USER_ID);
        assertThat(rootCommand.target()).isEqualTo(CommentTarget.novelChapter(CHAPTER_ID));
        assertThat(rootCommand.body()).isEqualTo(BODY);

        ArgumentCaptor<CreateChapterCommentBlockAnchorCommand> anchorCaptor = ArgumentCaptor.forClass(CreateChapterCommentBlockAnchorCommand.class);
        inOrder.verify(createChapterCommentBlockAnchorUseCase).execute(anchorCaptor.capture());
        CreateChapterCommentBlockAnchorCommand anchorCommand = anchorCaptor.getValue();
        assertThat(anchorCommand.rootCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(anchorCommand.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(anchorCommand.contentVersion()).isEqualTo(1L);
        assertThat(anchorCommand.blockKey()).isEqualTo(BLOCK_KEY);
    }

    @Test
    @DisplayName("If root creation fails, anchor creation is never attempted")
    void shouldNeverAttemptAnchorWhenRootCreationFails() {
        when(createRootCommentUseCase.execute(any()))
                .thenThrow(new CommentTargetNotEligibleException(CommentTarget.novelChapter(CHAPTER_ID)));

        assertThatThrownBy(() -> coordinator.createInlineComment(
                ACTOR_USER_ID,
                CHAPTER_ID,
                BODY,
                1L,
                BLOCK_KEY
        )).isInstanceOf(CommentTargetNotEligibleException.class);

        verify(createChapterCommentBlockAnchorUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("If anchor creation fails, exception propagates out of coordinator")
    void shouldPropagateExceptionWhenAnchorCreationFails() {
        Comment rootComment = Comment.createRoot(
                ROOT_COMMENT_ID,
                CommentTarget.novelChapter(CHAPTER_ID),
                ACTOR_USER_ID,
                BODY,
                Instant.now()
        );
        when(createRootCommentUseCase.execute(any())).thenReturn(rootComment);
        when(createChapterCommentBlockAnchorUseCase.execute(any()))
                .thenThrow(new ChapterCommentAnchorVersionConflictException(CHAPTER_ID, 1L, 2L));

        assertThatThrownBy(() -> coordinator.createInlineComment(
                ACTOR_USER_ID,
                CHAPTER_ID,
                BODY,
                1L,
                BLOCK_KEY
        )).isInstanceOf(ChapterCommentAnchorVersionConflictException.class);

        verify(createRootCommentUseCase).execute(any());
        verify(createChapterCommentBlockAnchorUseCase).execute(any());
    }
}
