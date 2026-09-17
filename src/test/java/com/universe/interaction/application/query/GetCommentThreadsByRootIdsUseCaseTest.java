package com.universe.interaction.application.query;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommentThreadsByRootIdsUseCase Unit Tests")
class GetCommentThreadsByRootIdsUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommentThreadVisibilityResolver visibilityResolver;

    private GetCommentThreadsByRootIdsUseCase useCase;

    private static final UUID TARGET_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(TARGET_ID);

    private static final UUID ROOT_1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AUTHOR_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-16T10:01:00Z");
    private static final Instant T2 = Instant.parse("2026-09-16T10:02:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetCommentThreadsByRootIdsUseCase(commentRepositoryPort, visibilityResolver);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when target is null")
    void shouldThrowWhenTargetIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, List.of(ROOT_1_ID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CommentTarget cannot be null.");

        verify(commentRepositoryPort, never()).findActiveRootsByIds(any(), any());
        verify(commentRepositoryPort, never()).findThreadRepliesByRootIds(any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when rootCommentIds is null")
    void shouldThrowWhenRootCommentIdsIsNull() {
        assertThatThrownBy(() -> useCase.execute(TARGET, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Root comment IDs collection cannot be null.");

        verify(commentRepositoryPort, never()).findActiveRootsByIds(any(), any());
        verify(commentRepositoryPort, never()).findThreadRepliesByRootIds(any());
    }

    @Test
    @DisplayName("Should return empty list immediately without querying repository when rootCommentIds is empty")
    void shouldReturnEmptyListWhenRootCommentIdsEmpty() {
        List<CommentThreadView> result = useCase.execute(TARGET, Collections.emptyList());

        assertThat(result).isNotNull().isEmpty();
        verify(commentRepositoryPort, never()).findActiveRootsByIds(any(), any());
        verify(commentRepositoryPort, never()).findThreadRepliesByRootIds(any());
    }

    @Test
    @DisplayName("Should return empty list when none of the requested roots exist or are active")
    void shouldReturnEmptyListWhenNoRootsFound() {
        when(commentRepositoryPort.findActiveRootsByIds(TARGET, List.of(ROOT_1_ID)))
                .thenReturn(Collections.emptyList());

        List<CommentThreadView> result = useCase.execute(TARGET, List.of(ROOT_1_ID));

        assertThat(result).isNotNull().isEmpty();
        verify(commentRepositoryPort).findActiveRootsByIds(TARGET, List.of(ROOT_1_ID));
        verify(commentRepositoryPort, never()).findThreadRepliesByRootIds(any());
        verify(visibilityResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("Should load root threads and query replies in exactly one batch query")
    void shouldLoadThreadsAndRepliesInBatch() {
        Comment root1 = Comment.createRoot(ROOT_1_ID, TARGET, AUTHOR_1, "Root 1", T1);
        Comment root2 = Comment.createRoot(ROOT_2_ID, TARGET, AUTHOR_2, "Root 2", T0);

        UUID reply1Id = UUID.randomUUID();
        UUID reply2Id = UUID.randomUUID();
        Comment reply1 = Comment.createReply(reply1Id, root1, AUTHOR_2, "Reply to root 1", T2);
        Comment reply2 = Comment.createReply(reply2Id, root2, AUTHOR_1, "Reply to root 2", T2);

        CommentReadItem itemReply1 = CommentReadItem.fromActiveReply(reply1, AUTHOR_1);
        CommentReadItem itemReply2 = CommentReadItem.fromActiveReply(reply2, AUTHOR_2);

        when(commentRepositoryPort.findActiveRootsByIds(TARGET, List.of(ROOT_1_ID, ROOT_2_ID)))
                .thenReturn(List.of(root1, root2));
        when(commentRepositoryPort.findThreadRepliesByRootIds(List.of(ROOT_1_ID, ROOT_2_ID)))
                .thenReturn(List.of(reply1, reply2));

        when(visibilityResolver.resolve(root1, List.of(reply1))).thenReturn(List.of(itemReply1));
        when(visibilityResolver.resolve(root2, List.of(reply2))).thenReturn(List.of(itemReply2));

        List<CommentThreadView> threads = useCase.execute(TARGET, List.of(ROOT_1_ID, ROOT_2_ID));

        assertThat(threads).hasSize(2);

        // Thread 1
        assertThat(threads.get(0).getRoot().getId()).isEqualTo(ROOT_1_ID);
        assertThat(threads.get(0).getRoot().getBody()).isEqualTo("Root 1");
        assertThat(threads.get(0).getReplies()).hasSize(1);
        assertThat(threads.get(0).getReplies().get(0).getId()).isEqualTo(reply1Id);

        // Thread 2
        assertThat(threads.get(1).getRoot().getId()).isEqualTo(ROOT_2_ID);
        assertThat(threads.get(1).getRoot().getBody()).isEqualTo("Root 2");
        assertThat(threads.get(1).getReplies()).hasSize(1);
        assertThat(threads.get(1).getReplies().get(0).getId()).isEqualTo(reply2Id);

        verify(commentRepositoryPort).findActiveRootsByIds(TARGET, List.of(ROOT_1_ID, ROOT_2_ID));
        verify(commentRepositoryPort).findThreadRepliesByRootIds(List.of(ROOT_1_ID, ROOT_2_ID));
        verify(visibilityResolver).resolve(root1, List.of(reply1));
        verify(visibilityResolver).resolve(root2, List.of(reply2));
    }

    @Test
    @DisplayName("Should handle roots with no replies without error")
    void shouldHandleRootsWithNoReplies() {
        Comment root1 = Comment.createRoot(ROOT_1_ID, TARGET, AUTHOR_1, "Root without replies", T0);

        when(commentRepositoryPort.findActiveRootsByIds(TARGET, List.of(ROOT_1_ID)))
                .thenReturn(List.of(root1));
        when(commentRepositoryPort.findThreadRepliesByRootIds(List.of(ROOT_1_ID)))
                .thenReturn(Collections.emptyList());
        when(visibilityResolver.resolve(root1, Collections.emptyList())).thenReturn(Collections.emptyList());

        List<CommentThreadView> threads = useCase.execute(TARGET, List.of(ROOT_1_ID));

        assertThat(threads).hasSize(1);
        assertThat(threads.get(0).getRoot().getId()).isEqualTo(ROOT_1_ID);
        assertThat(threads.get(0).getReplies()).isEmpty();
    }
}
