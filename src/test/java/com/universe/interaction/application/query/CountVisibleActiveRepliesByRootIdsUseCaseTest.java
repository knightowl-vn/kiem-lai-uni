package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CountVisibleActiveRepliesByRootIdsUseCase Unit Tests")
class CountVisibleActiveRepliesByRootIdsUseCaseTest {

    private static final UUID ROOT_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOT_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    private CountVisibleActiveRepliesByRootIdsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CountVisibleActiveRepliesByRootIdsUseCase(commentRepositoryPort);
    }

    @Test
    @DisplayName("execute: returns empty map when collection is null")
    void shouldReturnEmptyMapWhenNull() {
        Map<UUID, Long> result = useCase.execute(null);
        assertThat(result).isEmpty();
        verify(commentRepositoryPort, never()).countActiveRepliesByThreadRootIds(any());
    }

    @Test
    @DisplayName("execute: returns empty map when collection is empty")
    void shouldReturnEmptyMapWhenEmpty() {
        Map<UUID, Long> result = useCase.execute(Collections.emptyList());
        assertThat(result).isEmpty();
        verify(commentRepositoryPort, never()).countActiveRepliesByThreadRootIds(any());
    }

    @Test
    @DisplayName("execute: delegates to repository port and returns mapped reply counts")
    void shouldDelegateToRepositoryPort() {
        List<UUID> rootIds = List.of(ROOT_1, ROOT_2);
        when(commentRepositoryPort.countActiveRepliesByThreadRootIds(rootIds))
                .thenReturn(Map.of(ROOT_1, 3L, ROOT_2, 1L));

        Map<UUID, Long> result = useCase.execute(rootIds);

        assertThat(result).containsEntry(ROOT_1, 3L).containsEntry(ROOT_2, 1L);
        verify(commentRepositoryPort).countActiveRepliesByThreadRootIds(rootIds);
    }

    private static <T> java.util.Collection<T> any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
