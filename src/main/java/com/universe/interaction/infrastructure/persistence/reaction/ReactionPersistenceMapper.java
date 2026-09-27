package com.universe.interaction.infrastructure.persistence.reaction;

import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Persistence mapper converting between domain {@link Reaction} and {@link ReactionJpaEntity}.
 *
 * <p>Preserves all domain invariants during round trip:
 * <ul>
 *   <li>Exact scalar UUID &harr; CHAR(36) String conversion;</li>
 *   <li>Target type and target ID preservation;</li>
 *   <li>User ID preservation;</li>
 *   <li>Reaction type preservation;</li>
 *   <li>Timestamp preservation (createdAt, updatedAt);</li>
 *   <li>Rehydration via {@link Reaction#rehydrate}.</li>
 * </ul>
 */
@Component
public class ReactionPersistenceMapper {

    /**
     * Maps a domain {@link Reaction} to a {@link ReactionJpaEntity}.
     */
    public ReactionJpaEntity toJpaEntity(Reaction domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain reaction cannot be null.");
        }

        return new ReactionJpaEntity(
                domain.getId().toString(),
                domain.getUserId().toString(),
                domain.getTargetType().name(),
                domain.getTargetId().toString(),
                domain.getReactionType().name(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    /**
     * Reconstitutes a domain {@link Reaction} from a {@link ReactionJpaEntity}.
     */
    public Reaction toDomain(ReactionJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("ReactionJpaEntity cannot be null.");
        }

        UUID id = parseUuid(entity.getId(), "Reaction ID");
        UUID userId = parseUuid(entity.getUserId(), "User ID");
        ReactionTargetType targetType = parseTargetType(entity.getTargetType());
        UUID targetId = parseUuid(entity.getTargetId(), "Target ID");
        ReactionType reactionType = parseReactionType(entity.getReactionType());

        return Reaction.rehydrate(
                id,
                userId,
                new ReactionTarget(targetType, targetId),
                reactionType,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private UUID parseUuid(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(fieldName + " in database cannot be null or blank.");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(fieldName + " in database has invalid UUID format: " + raw, ex);
        }
    }

    private ReactionTargetType parseTargetType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Target type in database cannot be null or blank.");
        }
        try {
            return ReactionTargetType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid target type in database: " + raw, ex);
        }
    }

    private ReactionType parseReactionType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Reaction type in database cannot be null or blank.");
        }
        try {
            return ReactionType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid reaction type in database: " + raw, ex);
        }
    }
}
