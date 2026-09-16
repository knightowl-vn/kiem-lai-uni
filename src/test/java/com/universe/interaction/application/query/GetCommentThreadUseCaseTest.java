package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommentThreadUseCase Unit Tests")
class GetCommentThreadUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommentThreadVisibilityResolver visibilityResolver;

    private GetCommentThreadUseCase useCase;

    private static final UUID ROOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTHOR_ROOT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AUTHOR_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(UUID.fromString("99999999-9999-9999-9999-999999999999"));
    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-16T10:01:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetCommentThreadUseCase(commentRepositoryPort, visibilityResolver);
    }

    @Test
    @DisplayName("Should throw CommentNotFoundException when root is missing")
    void shouldThrowNotFoundWhenRootMissing() {
        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(ROOT_ID))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining(ROOT_ID.toString());

        verify(commentRepositoryPort, never()).findByIdForUpdate(any());
        verify(commentRepositoryPort, never()).findThreadReplies(any());
    }

    @Test
    @DisplayName("Should throw CommentThreadIntegrityException when supplied ID resolves to a reply comment")
    void shouldThrowIntegrityExceptionWhenCommentIsNotRoot() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
        UUID replyId = UUID.randomUUID();
        Comment reply = Comment.createReply(replyId, root, AUTHOR_B, "Reply", T1);

        when(commentRepositoryPort.findById(replyId)).thenReturn(Optional.of(reply));

        assertThatThrownBy(() -> useCase.execute(replyId))
                .isInstanceOf(CommentThreadIntegrityException.class)
                .hasMessageContaining("is not a root comment");

        verify(commentRepositoryPort, never()).findByIdForUpdate(any());
        verify(commentRepositoryPort, never()).findThreadReplies(any());
    }

    @Test
    @DisplayName("Should treat deleted root as not found / unavailable for normal read")
    void shouldTreatDeletedRootAsNotFound() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
        root.delete(T1);

        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));

        assertThatThrownBy(() -> useCase.execute(ROOT_ID))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining(ROOT_ID.toString());

        verify(commentRepositoryPort, never()).findByIdForUpdate(any());
        verify(commentRepositoryPort, never()).findThreadReplies(any());
    }

    @Test
    @DisplayName("Should return active root with empty replies list when thread has no replies")
    void shouldReturnRootWithEmptyRepliesWhenNoRepliesExist() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);

        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRepositoryPort.findThreadReplies(ROOT_ID)).thenReturn(Collections.emptyList());
        when(visibilityResolver.resolve(root, Collections.emptyList())).thenReturn(Collections.emptyList());

        CommentThreadView view = useCase.execute(ROOT_ID);

        assertThat(view).isNotNull();
        assertThat(view.getRoot().getId()).isEqualTo(ROOT_ID);
        assertThat(view.getRoot().getBody()).isEqualTo("Root body");
        assertThat(view.getRoot().isTombstone()).isFalse();
        assertThat(view.getReplies()).isEmpty();

        verify(commentRepositoryPort).findThreadReplies(ROOT_ID);
        verify(commentRepositoryPort, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Should return active root with resolver-filtered visible replies")
    void shouldReturnRootWithResolvedVisibleReplies() {
        Comment root = Comment.createRoot(ROOT_ID, TARGET, AUTHOR_ROOT, "Root body", T0);
        UUID replyBId = UUID.randomUUID();
        Comment replyB = Comment.createReply(replyBId, root, AUTHOR_B, "Reply B", T1);

        CommentReadItem itemB = CommentReadItem.fromActiveReply(replyB, AUTHOR_ROOT);

        when(commentRepositoryPort.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(commentRepositoryPort.findThreadReplies(ROOT_ID)).thenReturn(List.of(replyB));
        when(visibilityResolver.resolve(root, List.of(replyB))).thenReturn(List.of(itemB));

        CommentThreadView view = useCase.execute(ROOT_ID);

        assertThat(view).isNotNull();
        assertThat(view.getRoot().getId()).isEqualTo(ROOT_ID);
        assertThat(view.getReplies()).hasSize(1);
        assertThat(view.getReplies().get(0).getId()).isEqualTo(replyBId);
        assertThat(view.getReplies().get(0).getReplyToAuthorUserId()).isEqualTo(AUTHOR_ROOT);

        verify(commentRepositoryPort).findThreadReplies(ROOT_ID);
        verify(commentRepositoryPort, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Should reject null root ID input")
    void shouldRejectNullRootId() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Root comment ID cannot be null.");
    }
}
