package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ValidateCommentTargetScopeUseCase Unit Tests")
class ValidateCommentTargetScopeUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    private ValidateCommentTargetScopeUseCase useCase;

    private static final UUID COMMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_A_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHAPTER_B_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AUTHOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new ValidateCommentTargetScopeUseCase(commentRepositoryPort);
    }

    @Test
    @DisplayName("Should pass validation when comment belongs to the expected target")
    void shouldPassWhenCommentBelongsToTarget() {
        CommentTarget targetA = CommentTarget.novelChapter(CHAPTER_A_ID);
        Comment comment = Comment.createRoot(COMMENT_ID, targetA, AUTHOR_ID, "Discussion in chapter A", NOW);

        when(commentRepositoryPort.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

        assertThatCode(() -> useCase.execute(COMMENT_ID, targetA))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw CommentNotFoundException when comment does not exist")
    void shouldThrowNotFoundWhenCommentDoesNotExist() {
        CommentTarget targetA = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(commentRepositoryPort.findById(COMMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(COMMENT_ID, targetA))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("Comment not found: " + COMMENT_ID);
    }

    @Test
    @DisplayName("Should throw CommentNotFoundException when comment belongs to a different target")
    void shouldThrowNotFoundWhenCommentBelongsToDifferentTarget() {
        CommentTarget targetB = CommentTarget.novelChapter(CHAPTER_B_ID);
        Comment commentFromB = Comment.createRoot(COMMENT_ID, targetB, AUTHOR_ID, "Discussion in chapter B", NOW);

        when(commentRepositoryPort.findById(COMMENT_ID)).thenReturn(Optional.of(commentFromB));

        CommentTarget expectedTargetA = CommentTarget.novelChapter(CHAPTER_A_ID);

        assertThatThrownBy(() -> useCase.execute(COMMENT_ID, expectedTargetA))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("does not belong to target");
    }

    @Test
    @DisplayName("Should validate input arguments")
    void shouldValidateArguments() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);

        assertThatThrownBy(() -> useCase.execute(null, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Comment ID cannot be null.");

        assertThatThrownBy(() -> useCase.execute(COMMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected CommentTarget cannot be null.");
    }

    @Test
    @DisplayName("Should pass root validation when root comment belongs to the expected target")
    void shouldPassWhenRootCommentBelongsToTargetInExecuteRoot() {
        CommentTarget targetA = CommentTarget.novelChapter(CHAPTER_A_ID);
        Comment root = Comment.createRoot(COMMENT_ID, targetA, AUTHOR_ID, "Root discussion in chapter A", NOW);

        when(commentRepositoryPort.findById(COMMENT_ID)).thenReturn(Optional.of(root));

        assertThatCode(() -> useCase.executeRoot(COMMENT_ID, targetA))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw CommentNotFoundException when comment in executeRoot is a reply instead of a root")
    void shouldThrowNotFoundWhenCommentIsReplyInExecuteRoot() {
        CommentTarget targetA = CommentTarget.novelChapter(CHAPTER_A_ID);
        UUID parentId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        Comment root = Comment.createRoot(parentId, targetA, AUTHOR_ID, "Root comment", NOW);
        Comment reply = Comment.createReply(COMMENT_ID, root, AUTHOR_ID, "Reply comment", NOW.plusSeconds(10));

        when(commentRepositoryPort.findById(COMMENT_ID)).thenReturn(Optional.of(reply));

        assertThatThrownBy(() -> useCase.executeRoot(COMMENT_ID, targetA))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("is not a thread root");
    }

    @Test
    @DisplayName("Should throw CommentNotFoundException when root comment does not exist in executeRoot")
    void shouldThrowNotFoundWhenRootCommentDoesNotExistInExecuteRoot() {
        CommentTarget targetA = CommentTarget.novelChapter(CHAPTER_A_ID);
        when(commentRepositoryPort.findById(COMMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.executeRoot(COMMENT_ID, targetA))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("Comment not found: " + COMMENT_ID);
    }

    @Test
    @DisplayName("Should throw CommentNotFoundException when root comment belongs to a different target in executeRoot")
    void shouldThrowNotFoundWhenRootCommentBelongsToDifferentTargetInExecuteRoot() {
        CommentTarget targetB = CommentTarget.novelChapter(CHAPTER_B_ID);
        Comment commentFromB = Comment.createRoot(COMMENT_ID, targetB, AUTHOR_ID, "Discussion in chapter B", NOW);

        when(commentRepositoryPort.findById(COMMENT_ID)).thenReturn(Optional.of(commentFromB));

        CommentTarget expectedTargetA = CommentTarget.novelChapter(CHAPTER_A_ID);

        assertThatThrownBy(() -> useCase.executeRoot(COMMENT_ID, expectedTargetA))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("does not belong to target");
    }

    @Test
    @DisplayName("Should validate input arguments in executeRoot")
    void shouldValidateArgumentsInExecuteRoot() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_A_ID);

        assertThatThrownBy(() -> useCase.executeRoot(null, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Comment ID cannot be null.");

        assertThatThrownBy(() -> useCase.executeRoot(COMMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected CommentTarget cannot be null.");
    }
}
