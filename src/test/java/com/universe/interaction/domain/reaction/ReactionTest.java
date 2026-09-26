package com.universe.interaction.domain.reaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Reaction Domain Aggregate Unit Tests")
class ReactionTest {

    @Test
    @DisplayName("Should successfully create a new reaction with valid parameters")
    void shouldCreateNewReactionSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant createdAt = Instant.parse("2026-09-26T10:00:00Z");

        Reaction reaction = Reaction.create(id, userId, target, ReactionType.LOVE, createdAt);

        assertThat(reaction.getId()).isEqualTo(id);
        assertThat(reaction.getUserId()).isEqualTo(userId);
        assertThat(reaction.getTarget()).isEqualTo(target);
        assertThat(reaction.getTargetType()).isEqualTo(ReactionTargetType.NOVEL_CHAPTER);
        assertThat(reaction.getTargetId()).isEqualTo(target.targetId());
        assertThat(reaction.getReactionType()).isEqualTo(ReactionType.LOVE);
        assertThat(reaction.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reaction.getUpdatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("Should successfully rehydrate a reaction with separate updated timestamp")
    void shouldRehydrateReactionSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(UUID.randomUUID());
        Instant createdAt = Instant.parse("2026-09-26T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-26T11:30:00Z");

        Reaction reaction = Reaction.rehydrate(id, userId, target, ReactionType.FIRE, createdAt, updatedAt);

        assertThat(reaction.getId()).isEqualTo(id);
        assertThat(reaction.getUserId()).isEqualTo(userId);
        assertThat(reaction.getTarget()).isEqualTo(target);
        assertThat(reaction.getReactionType()).isEqualTo(ReactionType.FIRE);
        assertThat(reaction.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reaction.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should successfully change reaction type and update timestamp")
    void shouldChangeReactionTypeSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.donghuaEpisode(UUID.randomUUID());
        Instant createdAt = Instant.parse("2026-09-26T10:00:00Z");
        Instant changedAt = Instant.parse("2026-09-26T12:00:00Z");

        Reaction reaction = Reaction.create(id, userId, target, ReactionType.HAHA, createdAt);
        reaction.changeReactionType(ReactionType.SAD, changedAt);

        assertThat(reaction.getReactionType()).isEqualTo(ReactionType.SAD);
        assertThat(reaction.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reaction.getUpdatedAt()).isEqualTo(changedAt);
    }

    @Test
    @DisplayName("Should reject changeReactionType when timestamp is before last updatedAt")
    void shouldRejectChangeReactionTypeWithStaleTimestamp() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant createdAt = Instant.parse("2026-09-26T10:00:00Z");
        Instant staleTimestamp = Instant.parse("2026-09-26T09:00:00Z");

        Reaction reaction = Reaction.create(id, userId, target, ReactionType.LOVE, createdAt);

        assertThatThrownBy(() -> reaction.changeReactionType(ReactionType.FIRE, staleTimestamp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ChangedAt timestamp cannot be before the last updatedAt timestamp");
    }

    @Test
    @DisplayName("Should validate required non-null parameters upon creation")
    void shouldValidateNonNullParametersUponCreation() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant now = Instant.now();

        assertThatThrownBy(() -> Reaction.create(null, userId, target, ReactionType.LOVE, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Reaction ID cannot be null");

        assertThatThrownBy(() -> Reaction.create(id, null, target, ReactionType.LOVE, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("User ID cannot be null");

        assertThatThrownBy(() -> Reaction.create(id, userId, null, ReactionType.LOVE, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Reaction target cannot be null");

        assertThatThrownBy(() -> Reaction.create(id, userId, target, null, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Reaction type cannot be null");

        assertThatThrownBy(() -> Reaction.create(id, userId, target, ReactionType.LOVE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CreatedAt timestamp cannot be null");
    }

    @Test
    @DisplayName("Should reject rehydration when updatedAt is before createdAt")
    void shouldRejectRehydrationWithInvalidTimestamps() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant createdAt = Instant.parse("2026-09-26T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-26T09:00:00Z");

        assertThatThrownBy(() -> Reaction.rehydrate(id, userId, target, ReactionType.LOVE, createdAt, updatedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before createdAt timestamp");
    }

    @Test
    @DisplayName("Should implement equality and hashCode based on identity ID")
    void shouldImplementEqualsAndHashCodeBasedOnId() {
        UUID id = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(UUID.randomUUID());
        Instant now = Instant.now();

        Reaction r1 = Reaction.create(id, user1, target, ReactionType.LOVE, now);
        Reaction r2 = Reaction.create(id, user2, target, ReactionType.FIRE, now);
        Reaction r3 = Reaction.create(UUID.randomUUID(), user1, target, ReactionType.LOVE, now);

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        assertThat(r1).isNotEqualTo(r3);
    }
}
