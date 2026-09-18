package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeleteCommentUseCase Unit Tests")
class DeleteCommentUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommentRevisionRepositoryPort commentRevisionRepositoryPort;

    @Mock
    private ClockPort clockPort;

    private DeleteCommentUseCase useCase;

    private static final UUID AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(UUID.randomUUID());
    private static final Instant T1 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-16T10:05:00Z");
    private static final Instant T3 = Instant.parse("2026-09-16T10:30:00Z");

    @BeforeEach
    void setUp() {
        useCase = new DeleteCommentUseCase(commentRepositoryPort, commentRevisionRepositoryPort, clockPort);
    }

    @Test
    @DisplayName("Should soft-delete (tombstone) own active comment, purge revisions, and preserve ancestry with null body")
    void shouldDeleteOwnActiveCommentSuccessfully() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, UUID.randomUUID(), "Root body", T1);
        Comment reply = Comment.createReply(REPLY_ID, root, AUTHOR_ID, "Reply body", T2);
        DeleteCommentCommand command = new DeleteCommentCommand(AUTHOR_ID, REPLY_ID);

        when(commentRepositoryPort.findByIdForUpdate(REPLY_ID)).thenReturn(Optional.of(reply));
        when(clockPort.now()).thenReturn(T3);
        when(commentRepositoryPort.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment tombstone = useCase.execute(command);

        assertThat(tombstone.getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(tombstone.isDeleted()).isTrue();
        assertThat(tombstone.getBody()).isNull();
        assertThat(tombstone.getParentCommentId()).isEqualTo(ROOT_ID);
        assertThat(tombstone.getThreadRootCommentId()).isEqualTo(ROOT_ID);
        assertThat(tombstone.getCreatedAt()).isEqualTo(T2);
        assertThat(tombstone.getUpdatedAt()).isEqualTo(T3);
        assertThat(tombstone.getDeletedAt()).isEqualTo(T3);

        verify(commentRepositoryPort).findByIdForUpdate(REPLY_ID);
        verify(commentRevisionRepositoryPort).deleteAllByCommentId(REPLY_ID);
        verify(commentRepositoryPort).save(reply);
    }

    @Test
    @DisplayName("Should preserve idempotency on repeated delete without updating timestamp, saving, or re-purging revisions")
    void shouldPreserveIdempotencyOnRepeatedDelete() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ID, "Root body", T1);
        root.delete(T2); // Already deleted at T2

        DeleteCommentCommand command = new DeleteCommentCommand(AUTHOR_ID, ROOT_ID);

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));

        Comment result = useCase.execute(command);

        assertThat(result.isDeleted()).isTrue();
        assertThat(result.getDeletedAt()).isEqualTo(T2);
        assertThat(result.getUpdatedAt()).isEqualTo(T2);

        // Does not call commentRevisionRepositoryPort.deleteAllByCommentId(), clockPort.now() or commentRepositoryPort.save()
        verify(commentRevisionRepositoryPort, never()).deleteAllByCommentId(any());
        verify(clockPort, never()).now();
        verify(commentRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should reject delete when actor is not the author")
    void shouldRejectWhenActorIsNotAuthor() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ID, "Root body", T1);
        DeleteCommentCommand command = new DeleteCommentCommand(OTHER_USER_ID, ROOT_ID);

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentMutationForbiddenException.class)
                .hasMessageContaining("is not the author of comment");

        verify(commentRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should reject delete when comment is not found")
    void shouldRejectWhenCommentNotFound() {
        UUID missingId = UUID.randomUUID();
        DeleteCommentCommand command = new DeleteCommentCommand(AUTHOR_ID, missingId);

        when(commentRepositoryPort.findByIdForUpdate(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("Comment not found");
    }

    @Test
    @DisplayName("Should validate command inputs")
    void shouldValidateCommandInputs() {
        assertThatThrownBy(() -> new DeleteCommentCommand(null, ROOT_ID))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new DeleteCommentCommand(AUTHOR_ID, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class);
    }
}
