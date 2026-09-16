package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EditCommentUseCase Unit Tests")
class EditCommentUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private ClockPort clockPort;

    private EditCommentUseCase useCase;

    private static final UUID AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(UUID.randomUUID());
    private static final Instant T1 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-16T10:30:00Z");

    @BeforeEach
    void setUp() {
        useCase = new EditCommentUseCase(commentRepositoryPort, clockPort);
    }

    @Test
    @DisplayName("Should edit own active comment and update timestamp from ClockPort")
    void shouldEditOwnActiveCommentSuccessfully() {
        Comment comment = Comment.createRoot(COMMENT_ID, TARGET, AUTHOR_ID, "Original body", T1);
        EditCommentCommand command = new EditCommentCommand(AUTHOR_ID, COMMENT_ID, "Edited body");

        when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID)).thenReturn(Optional.of(comment));
        when(clockPort.now()).thenReturn(T2);
        when(commentRepositoryPort.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment result = useCase.execute(command);

        assertThat(result.getBody()).isEqualTo("Edited body");
        assertThat(result.getCreatedAt()).isEqualTo(T1);
        assertThat(result.getUpdatedAt()).isEqualTo(T2);

        verify(commentRepositoryPort).findByIdForUpdate(COMMENT_ID);
        verify(commentRepositoryPort).save(comment);
    }

    @Test
    @DisplayName("Should reject edit when actor is not the comment author")
    void shouldRejectWhenActorIsNotAuthor() {
        Comment comment = Comment.createRoot(COMMENT_ID, TARGET, AUTHOR_ID, "Original body", T1);
        EditCommentCommand command = new EditCommentCommand(OTHER_USER_ID, COMMENT_ID, "Hacked body");

        when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentMutationForbiddenException.class)
                .hasMessageContaining("is not the author of comment");
    }

    @Test
    @DisplayName("Should reject edit when comment is not found")
    void shouldRejectWhenCommentNotFound() {
        UUID missingId = UUID.randomUUID();
        EditCommentCommand command = new EditCommentCommand(AUTHOR_ID, missingId, "Body");

        when(commentRepositoryPort.findByIdForUpdate(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("Comment not found");
    }

    @Test
    @DisplayName("Should reject edit when comment is already deleted")
    void shouldRejectWhenCommentIsDeleted() {
        Comment comment = Comment.createRoot(COMMENT_ID, TARGET, AUTHOR_ID, "Original body", T1);
        comment.delete(T2);
        EditCommentCommand command = new EditCommentCommand(AUTHOR_ID, COMMENT_ID, "Trying to edit deleted");

        when(commentRepositoryPort.findByIdForUpdate(COMMENT_ID)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentMutationForbiddenException.class)
                .hasMessageContaining("Cannot edit a deleted comment");
    }

    @Test
    @DisplayName("Should validate command inputs")
    void shouldValidateCommandInputs() {
        assertThatThrownBy(() -> new EditCommentCommand(null, COMMENT_ID, "Body"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new EditCommentCommand(AUTHOR_ID, null, "Body"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new EditCommentCommand(AUTHOR_ID, COMMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new EditCommentCommand(AUTHOR_ID, COMMENT_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class);
    }
}
