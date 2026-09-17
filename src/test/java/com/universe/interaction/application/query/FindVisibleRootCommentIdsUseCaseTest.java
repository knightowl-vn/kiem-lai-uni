package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FindVisibleRootCommentIdsUseCase Unit Tests")
class FindVisibleRootCommentIdsUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    private FindVisibleRootCommentIdsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new FindVisibleRootCommentIdsUseCase(commentRepositoryPort);
    }

    @Test
    @DisplayName("execute: throws IllegalArgumentException when target is null")
    void shouldThrowWhenTargetNull() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CommentTarget cannot be null");
    }

    @Test
    @DisplayName("execute: returns immutable set of active root comment IDs")
    void shouldReturnActiveRootIds() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        when(commentRepositoryPort.findActiveRootCommentIds(target)).thenReturn(List.of(ROOT_1, ROOT_2));

        Set<UUID> result = useCase.execute(target);

        assertThat(result).containsExactlyInAnyOrder(ROOT_1, ROOT_2);
        verify(commentRepositoryPort).findActiveRootCommentIds(target);
    }

    @Test
    @DisplayName("execute: returns empty set when no active roots exist")
    void shouldReturnEmptySetWhenNoActiveRoots() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        when(commentRepositoryPort.findActiveRootCommentIds(target)).thenReturn(List.of());

        Set<UUID> result = useCase.execute(target);

        assertThat(result).isEmpty();
        verify(commentRepositoryPort).findActiveRootCommentIds(target);
    }
}
