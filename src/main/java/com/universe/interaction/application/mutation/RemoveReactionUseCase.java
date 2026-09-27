package com.universe.interaction.application.mutation;

import com.universe.interaction.application.ports.ReactionRepositoryPort;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Use case to remove an authenticated user's reaction from a target.
 *
 * <p>Enforces idempotent removal semantics:
 * <ul>
 *   <li>Does not require target eligibility checks (allows users to clean up reactions on deleted/archived targets);</li>
 *   <li>Returns {@code true} if a reaction was removed, or {@code false} if no reaction was present (no-op).</li>
 * </ul>
 */
@Service
public class RemoveReactionUseCase {

    private final ReactionRepositoryPort reactionRepositoryPort;

    public RemoveReactionUseCase(ReactionRepositoryPort reactionRepositoryPort) {
        this.reactionRepositoryPort = Objects.requireNonNull(reactionRepositoryPort, "ReactionRepositoryPort cannot be null.");
    }

    public boolean execute(RemoveReactionCommand command) {
        Objects.requireNonNull(command, "RemoveReactionCommand cannot be null.");

        return reactionRepositoryPort.deleteByUserAndTarget(
                command.userId(),
                command.target()
        );
    }
}
