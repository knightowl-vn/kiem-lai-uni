package com.universe.interaction.application.mutation;

import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.reaction.ReactionTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RemoveReactionUseCase Unit Tests")
class RemoveReactionUseCaseTest {

    @Mock
    private ReactionRepositoryPort reactionRepositoryPort;

    private RemoveReactionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RemoveReactionUseCase(reactionRepositoryPort);
    }

    @Test
    @DisplayName("Should return true when existing reaction is successfully removed")
    void shouldReturnTrueWhenReactionIsRemoved() {
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());

        when(reactionRepositoryPort.deleteByUserAndTarget(userId, target)).thenReturn(true);

        RemoveReactionCommand command = new RemoveReactionCommand(userId, target);
        boolean result = useCase.execute(command);

        assertThat(result).isTrue();
        verify(reactionRepositoryPort).deleteByUserAndTarget(userId, target);
    }

    @Test
    @DisplayName("Should return false when no reaction existed on the target (idempotent no-op)")
    void shouldReturnFalseWhenNoReactionExisted() {
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(UUID.randomUUID());

        when(reactionRepositoryPort.deleteByUserAndTarget(userId, target)).thenReturn(false);

        RemoveReactionCommand command = new RemoveReactionCommand(userId, target);
        boolean result = useCase.execute(command);

        assertThat(result).isFalse();
        verify(reactionRepositoryPort).deleteByUserAndTarget(userId, target);
    }

    @Test
    @DisplayName("Should reject null command and null parameters")
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("RemoveReactionCommand cannot be null");

        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());

        assertThatThrownBy(() -> new RemoveReactionCommand(null, target))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("User ID cannot be null");

        assertThatThrownBy(() -> new RemoveReactionCommand(userId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ReactionTarget cannot be null");
    }
}
