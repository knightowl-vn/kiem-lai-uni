package com.universe.interaction.domain.reaction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing an authenticated user's reaction to a content target.
 *
 * <p>Invariants:
 * <ul>
 *   <li>One user + one target = at most one current reaction;</li>
 *   <li>Reaction type belongs to {@link ReactionType};</li>
 *   <li>Target is a generic {@link ReactionTarget} (targetType + scalar targetId);</li>
 *   <li>Timestamps: updatedAt &gt;= createdAt;</li>
 *   <li>Current-state model: mutating reaction updates reactionType and updatedAt.</li>
 * </ul>
 *
 * <p>This domain model is pure Java and framework-free.
 */
public final class Reaction {

    private final UUID id;
    private final UUID userId;
    private final ReactionTarget target;
    private ReactionType reactionType;
    private final Instant createdAt;
    private Instant updatedAt;

    private Reaction(
            UUID id,
            UUID userId,
            ReactionTarget target,
            ReactionType reactionType,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "Reaction ID cannot be null.");
        this.userId = Objects.requireNonNull(userId, "User ID cannot be null.");
        this.target = Objects.requireNonNull(target, "Reaction target cannot be null.");
        this.reactionType = Objects.requireNonNull(reactionType, "Reaction type cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");

        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
    }

    /**
     * Factory method creating a new Reaction.
     */
    public static Reaction create(
            UUID id,
            UUID userId,
            ReactionTarget target,
            ReactionType reactionType,
            Instant createdAt
    ) {
        return new Reaction(id, userId, target, reactionType, createdAt, createdAt);
    }

    /**
     * Rehydrates a Reaction from persistence.
     */
    public static Reaction rehydrate(
            UUID id,
            UUID userId,
            ReactionTarget target,
            ReactionType reactionType,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new Reaction(id, userId, target, reactionType, createdAt, updatedAt);
    }

    /**
     * Changes the emotional reaction type.
     */
    public void changeReactionType(ReactionType newReactionType, Instant changedAt) {
        Objects.requireNonNull(newReactionType, "New reaction type cannot be null.");
        Objects.requireNonNull(changedAt, "ChangedAt timestamp cannot be null.");

        if (changedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("ChangedAt timestamp cannot be before the last updatedAt timestamp.");
        }

        this.reactionType = newReactionType;
        this.updatedAt = changedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public ReactionTarget getTarget() {
        return target;
    }

    public ReactionTargetType getTargetType() {
        return target.type();
    }

    public UUID getTargetId() {
        return target.targetId();
    }

    public ReactionType getReactionType() {
        return reactionType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Reaction reaction = (Reaction) o;
        return Objects.equals(id, reaction.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Reaction{" +
                "id=" + id +
                ", userId=" + userId +
                ", target=" + target +
                ", reactionType=" + reactionType +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
