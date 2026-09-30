package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.DuplicateReactionException;
import com.universe.interaction.application.exceptions.ReactionTargetNotEligibleException;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case to orchestrate setting the desired emotional reaction on an eligible target.
 *
 * <p>Concurrency & Barrier Guarantees:
 * <ul>
 *   <li>When target is {@link ReactionTargetType#COMMUNITY_POST} or {@link ReactionTargetType#COMMENT},
 *       delegates to {@link LockedReactionMutationExecutor}, which executes inside a dedicated transaction
 *       holding the authoritative row lock through lookup and database write;</li>
 *   <li>For un-locked legacy targets (e.g. {@link ReactionTargetType#NOVEL_CHAPTER}), runs without an outer
 *       transaction and recovers deterministically under first-insert races ({@link DuplicateReactionException}).</li>
 * </ul>
 */
@Service
public class SetReactionUseCase {

    private final LockedReactionMutationExecutor lockedReactionMutationExecutor;
    private final ReactionRepositoryPort reactionRepositoryPort;
    private final ReactionTargetEligibilityPort eligibilityPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public SetReactionUseCase(
            LockedReactionMutationExecutor lockedReactionMutationExecutor,
            ReactionRepositoryPort reactionRepositoryPort,
            ReactionTargetEligibilityPort eligibilityPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.lockedReactionMutationExecutor = Objects.requireNonNull(lockedReactionMutationExecutor, "LockedReactionMutationExecutor cannot be null.");
        this.reactionRepositoryPort = Objects.requireNonNull(reactionRepositoryPort, "ReactionRepositoryPort cannot be null.");
        this.eligibilityPort = Objects.requireNonNull(eligibilityPort, "ReactionTargetEligibilityPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public Reaction execute(SetReactionCommand command) {
        Objects.requireNonNull(command, "SetReactionCommand cannot be null.");

        // 1. If target requires exclusive mutation barrier (COMMUNITY_POST or COMMENT), delegate to transactional executor
        if (command.target().type() == ReactionTargetType.COMMUNITY_POST
                || command.target().type() == ReactionTargetType.COMMENT) {
            return lockedReactionMutationExecutor.executeLocked(command);
        }

        // 2. Legacy / un-locked target eligibility check (e.g. NOVEL_CHAPTER)
        if (!eligibilityPort.isEligible(command.target())) {
            throw new ReactionTargetNotEligibleException(command.target());
        }

        // 3. Look for existing reaction
        Optional<Reaction> existingOpt = reactionRepositoryPort.findByUserAndTarget(
                command.userId(),
                command.target()
        );

        if (existingOpt.isEmpty()) {
            // CASE A: None exists -> create new
            UUID reactionId = idGeneratorPort.generate();
            Instant now = clockPort.now();
            Reaction newReaction = Reaction.create(
                    reactionId,
                    command.userId(),
                    command.target(),
                    command.reactionType(),
                    now
            );

            try {
                return reactionRepositoryPort.save(newReaction);
            } catch (DuplicateReactionException ex) {
                // Race condition: another concurrent thread inserted between lookup and save
                return recoverFromDuplicateInsert(command);
            }
        }

        Reaction existing = existingOpt.get();

        // CASE B: Existing has same type -> idempotent no-op (no updatedAt churn or DB write)
        if (existing.getReactionType() == command.reactionType()) {
            return existing;
        }

        // CASE C: Existing has different type -> mutate and save existing aggregate
        Instant now = clockPort.now();
        existing.changeReactionType(command.reactionType(), now);
        return reactionRepositoryPort.save(existing);
    }

    private Reaction recoverFromDuplicateInsert(SetReactionCommand command) {
        Reaction authoritative = reactionRepositoryPort.findByUserAndTarget(
                command.userId(),
                command.target()
        ).orElseThrow(() -> new IllegalStateException(
                "Duplicate reaction collision occurred but authoritative reaction was not found for target " + command.target().type()
        ));

        if (authoritative.getReactionType() == command.reactionType()) {
            return authoritative;
        }

        Instant now = clockPort.now();
        authoritative.changeReactionType(command.reactionType(), now);
        return reactionRepositoryPort.save(authoritative);
    }
}
