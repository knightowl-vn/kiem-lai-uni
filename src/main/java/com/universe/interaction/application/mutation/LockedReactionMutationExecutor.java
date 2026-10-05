package com.universe.interaction.application.mutation;

import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.interaction.application.exceptions.ReactionTargetNotEligibleException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional executor handling reaction mutations on targets that require an exclusive
 * mutation barrier ({@link ReactionTargetType#COMMUNITY_POST} and {@link ReactionTargetType#COMMENT}).
 *
 * <p>Concurrency & Barrier Guarantees:
 * <ul>
 *   <li>Executes within a dedicated Spring transaction;</li>
 *   <li>When target is {@link ReactionTargetType#COMMUNITY_POST}, acquires the Community post
 *       {@code PESSIMISTIC_WRITE} barrier via {@link CommunityPostInteractionMutationPort};</li>
 *   <li>When target is {@link ReactionTargetType#COMMENT}, acquires the Comment
 *       {@code PESSIMISTIC_WRITE} lock via {@link CommentRepositoryPort#findByIdForUpdate};</li>
 *   <li>The acquired target lock remains held continuously throughout existing reaction lookup,
 *       mutation, and database write until the transaction commits;</li>
 *   <li>Because all concurrent writers for the same post or comment serialize on the target row lock,
 *       first-insert race collisions are prevented at the row barrier level.</li>
 * </ul>
 */
@Component
public class LockedReactionMutationExecutor {

    private final CommunityPostInteractionMutationPort communityPostMutationPort;
    private final CommentRepositoryPort commentRepositoryPort;
    private final ReactionRepositoryPort reactionRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public LockedReactionMutationExecutor(
            CommunityPostInteractionMutationPort communityPostMutationPort,
            CommentRepositoryPort commentRepositoryPort,
            ReactionRepositoryPort reactionRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.communityPostMutationPort = Objects.requireNonNull(communityPostMutationPort, "CommunityPostInteractionMutationPort cannot be null.");
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.reactionRepositoryPort = Objects.requireNonNull(reactionRepositoryPort, "ReactionRepositoryPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public Reaction executeLocked(SetReactionCommand command) {
        Objects.requireNonNull(command, "SetReactionCommand cannot be null.");

        // 1. Authoritative mutation barrier under mandatory transaction
        if (command.target().type() == ReactionTargetType.COMMUNITY_POST) {
            communityPostMutationPort.lockExistingPostForInteraction(command.target().targetId())
                    .orElseThrow(() -> new ReactionTargetNotEligibleException(command.target()));
        } else if (command.target().type() == ReactionTargetType.COMMENT) {
            Comment comment = commentRepositoryPort.findByIdForUpdate(command.target().targetId())
                    .orElseThrow(() -> new ReactionTargetNotEligibleException(command.target()));
            if (comment.isDeleted()) {
                throw new ReactionTargetNotEligibleException(command.target());
            }
        } else {
            throw new IllegalArgumentException("Unsupported locked reaction target type: " + command.target().type());
        }

        // 2. Look for existing reaction under target lock
        Optional<Reaction> existingOpt = reactionRepositoryPort.findByUserAndTarget(
                command.userId(),
                command.target()
        );

        if (existingOpt.isEmpty()) {
            UUID reactionId = idGeneratorPort.generate();
            Instant now = clockPort.now();
            Reaction newReaction = Reaction.create(
                    reactionId,
                    command.userId(),
                    command.target(),
                    command.reactionType(),
                    now
            );
            return reactionRepositoryPort.save(newReaction);
        }

        Reaction existing = existingOpt.get();

        if (existing.getReactionType() == command.reactionType()) {
            return existing;
        }

        Instant now = clockPort.now();
        existing.changeReactionType(command.reactionType(), now);
        return reactionRepositoryPort.save(existing);
    }
}
