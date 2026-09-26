package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentHasRepliesException;
import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeleteCommentUseCase Unit Tests — Leaf Hard Deletion & Reply Protection")
class DeleteCommentUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommentRevisionRepositoryPort commentRevisionRepositoryPort;

    @Mock
    private ReactionRepositoryPort reactionRepositoryPort;

    private DeleteCommentUseCase useCase;

    private static final UUID AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REPLY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(UUID.randomUUID());
    private static final Instant T1 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-16T10:05:00Z");

    @BeforeEach
    void setUp() {
        useCase = new DeleteCommentUseCase(commentRepositoryPort, commentRevisionRepositoryPort, reactionRepositoryPort);
    }

    @Test
    @DisplayName("1. Author leaf delete physically removes row, reactions, and revisions")
    void shouldPhysicallyDeleteLeafCommentAndCleanupReactionsAndRevisions() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ID, "Root leaf body", T1);
        DeleteCommentCommand command = new DeleteCommentCommand(AUTHOR_ID, ROOT_ID);

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRepositoryPort.hasDescendants(ROOT_ID)).thenReturn(false);

        useCase.execute(command);

        verify(commentRepositoryPort).findByIdForUpdate(ROOT_ID);
        verify(commentRepositoryPort).hasDescendants(ROOT_ID);

        // Verify reaction deletion for leaf only
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> reactionCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(reactionRepositoryPort).deleteAllByTargetIds(eq(ReactionTargetType.COMMENT), reactionCaptor.capture());
        assertThat(reactionCaptor.getValue()).containsExactly(ROOT_ID);

        // Verify revision deletion for leaf only
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> revisionCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(commentRevisionRepositoryPort).deleteAllByCommentIds(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue()).containsExactly(ROOT_ID);

        // Verify physical comment row deletion for leaf only
        verify(commentRepositoryPort).deleteById(ROOT_ID);
    }

    @Test
    @DisplayName("2. Author root with another user's reply is rejected and removes NOTHING")
    void shouldRejectDeleteWhenRootCommentHasReplies() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ID, "Root with replies", T1);
        DeleteCommentCommand command = new DeleteCommentCommand(AUTHOR_ID, ROOT_ID);

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRepositoryPort.hasDescendants(ROOT_ID)).thenReturn(true);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentHasRepliesException.class)
                .hasMessage("Không thể xóa bình luận đang có phản hồi.");

        verify(commentRepositoryPort, never()).deleteById(any());
        verify(commentRepositoryPort, never()).deleteAllByIds(any());
        verify(reactionRepositoryPort, never()).deleteAllByTargetIds(any(), any());
        verify(commentRevisionRepositoryPort, never()).deleteAllByCommentIds(any());
    }

    @Test
    @DisplayName("3. Author reply with descendant is rejected and removes NOTHING")
    void shouldRejectDeleteWhenReplyCommentHasDescendants() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, OTHER_USER_ID, "Root body", T1);
        Comment reply = Comment.createReply(REPLY_ID, root, AUTHOR_ID, "Reply with child", T2);
        DeleteCommentCommand command = new DeleteCommentCommand(AUTHOR_ID, REPLY_ID);

        when(commentRepositoryPort.findByIdForUpdate(REPLY_ID)).thenReturn(Optional.of(reply));
        when(commentRepositoryPort.hasDescendants(REPLY_ID)).thenReturn(true);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentHasRepliesException.class)
                .hasMessage("Không thể xóa bình luận đang có phản hồi.");

        verify(commentRepositoryPort, never()).deleteById(any());
        verify(commentRepositoryPort, never()).deleteAllByIds(any());
        verify(reactionRepositoryPort, never()).deleteAllByTargetIds(any(), any());
        verify(commentRevisionRepositoryPort, never()).deleteAllByCommentIds(any());
    }

    @Test
    @DisplayName("4. Non-author cannot delete comment")
    void shouldRejectWhenActorIsNotAuthor() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ID, "Root body", T1);
        DeleteCommentCommand command = new DeleteCommentCommand(OTHER_USER_ID, ROOT_ID);

        when(commentRepositoryPort.findByIdForUpdate(ROOT_ID)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentMutationForbiddenException.class)
                .hasMessageContaining("is not the author of comment");

        verify(commentRepositoryPort, never()).hasDescendants(any());
        verify(commentRepositoryPort, never()).deleteById(any());
        verify(commentRepositoryPort, never()).deleteAllByIds(any());
        verify(reactionRepositoryPort, never()).deleteAllByTargetIds(any(), any());
        verify(commentRevisionRepositoryPort, never()).deleteAllByCommentIds(any());
    }

    @Test
    @DisplayName("5. Reject delete when comment is not found")
    void shouldRejectWhenCommentNotFound() {
        UUID missingId = UUID.randomUUID();
        DeleteCommentCommand command = new DeleteCommentCommand(AUTHOR_ID, missingId);

        when(commentRepositoryPort.findByIdForUpdate(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining("Comment not found");
    }

    @Test
    @DisplayName("6. Validate command inputs")
    void shouldValidateCommandInputs() {
        assertThatThrownBy(() -> new DeleteCommentCommand(null, ROOT_ID))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new DeleteCommentCommand(AUTHOR_ID, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class);
    }
}
