package com.universe.interaction.infrastructure.persistence.reaction;

import com.universe.interaction.application.exceptions.DuplicateReactionException;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class
})
@DisplayName("Reaction JPA Persistence Adapter Integration Tests")
class ReactionJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReactionPersistenceAdapter adapter;

    @BeforeEach
    @AfterEach
    void cleanData() {
        jdbcTemplate.execute("DELETE FROM interaction_reactions;");
    }

    @Test
    @DisplayName("Should save and find reaction by ID and by User + Target across supported targets")
    void shouldSaveAndFindReaction() {
        UUID reactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(chapterId);
        Instant createdAt = Instant.parse("2026-09-26T10:00:00Z");

        Reaction reaction = Reaction.create(reactionId, userId, target, ReactionType.LOVE, createdAt);
        Reaction saved = adapter.save(reaction);

        assertThat(saved.getId()).isEqualTo(reactionId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTarget()).isEqualTo(target);
        assertThat(saved.getReactionType()).isEqualTo(ReactionType.LOVE);

        // Find by ID
        Optional<Reaction> foundById = adapter.findById(reactionId);
        assertThat(foundById).isPresent();
        assertThat(foundById.get().getId()).isEqualTo(reactionId);
        assertThat(foundById.get().getReactionType()).isEqualTo(ReactionType.LOVE);
        assertThat(foundById.get().getCreatedAt()).isEqualTo(createdAt);
        assertThat(foundById.get().getUpdatedAt()).isEqualTo(createdAt);

        // Find by User and Target
        Optional<Reaction> foundByUserAndTarget = adapter.findByUserAndTarget(userId, target);
        assertThat(foundByUserAndTarget).isPresent();
        assertThat(foundByUserAndTarget.get().getId()).isEqualTo(reactionId);

        // Find User Reaction Type directly
        Optional<ReactionType> userReactionType = adapter.findUserReactionType(userId, target);
        assertThat(userReactionType).contains(ReactionType.LOVE);
    }

    @Test
    @DisplayName("Should update reaction type on existing aggregate (last-write-wins)")
    void shouldUpdateReactionType() {
        UUID reactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(UUID.randomUUID());
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T10:15:00Z");

        Reaction reaction = Reaction.create(reactionId, userId, target, ReactionType.FIRE, t1);
        adapter.save(reaction);

        reaction.changeReactionType(ReactionType.HAHA, t2);
        Reaction updated = adapter.save(reaction);

        assertThat(updated.getReactionType()).isEqualTo(ReactionType.HAHA);
        assertThat(updated.getUpdatedAt()).isEqualTo(t2);

        Optional<Reaction> reloaded = adapter.findById(reactionId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getReactionType()).isEqualTo(ReactionType.HAHA);
        assertThat(reloaded.get().getCreatedAt()).isEqualTo(t1);
        assertThat(reloaded.get().getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Should delete reaction aggregate and delete by user and target")
    void shouldDeleteReactions() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.donghuaEpisode(UUID.randomUUID());
        Instant now = Instant.now();

        Reaction r1 = adapter.save(Reaction.create(UUID.randomUUID(), user1, target, ReactionType.LOVE, now));
        Reaction r2 = adapter.save(Reaction.create(UUID.randomUUID(), user2, target, ReactionType.SAD, now));

        // Delete r1 via aggregate
        adapter.delete(r1);
        assertThat(adapter.findById(r1.getId())).isEmpty();
        assertThat(adapter.findByUserAndTarget(user1, target)).isEmpty();

        // Delete r2 via user and target
        boolean deleted = adapter.deleteByUserAndTarget(user2, target);
        assertThat(deleted).isTrue();
        assertThat(adapter.findById(r2.getId())).isEmpty();
        assertThat(adapter.findByUserAndTarget(user2, target)).isEmpty();

        // Second delete returns false
        boolean deleteNonExistent = adapter.deleteByUserAndTarget(user2, target);
        assertThat(deleteNonExistent).isFalse();
    }

    @Test
    @DisplayName("Should throw DuplicateReactionException when inserting duplicate reaction for same user and target")
    void shouldThrowDuplicateReactionExceptionOnUniqueConstraintViolation() {
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant now = Instant.now();

        Reaction r1 = Reaction.create(UUID.randomUUID(), userId, target, ReactionType.LOVE, now);
        adapter.save(r1);

        Reaction r2 = Reaction.create(UUID.randomUUID(), userId, target, ReactionType.FIRE, now);
        assertThatThrownBy(() -> adapter.save(r2))
                .isInstanceOf(DuplicateReactionException.class)
                .satisfies(ex -> {
                    DuplicateReactionException dre = (DuplicateReactionException) ex;
                    assertThat(dre.getUserId()).isEqualTo(userId);
                    assertThat(dre.getTarget()).isEqualTo(target);
                });
    }

    @Test
    @DisplayName("Should count reactions grouped by type for a single target with zero counts for unreacted types")
    void shouldCountReactionsGroupedByTypeForSingleTarget() {
        ReactionTarget target1 = ReactionTarget.novelChapter(UUID.randomUUID());
        ReactionTarget target2 = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant now = Instant.now();

        // target1 reactions: 2 LOVE, 1 FIRE, 3 HAHA, 0 SAD
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target1, ReactionType.LOVE, now));
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target1, ReactionType.LOVE, now));
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target1, ReactionType.FIRE, now));
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target1, ReactionType.HAHA, now));
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target1, ReactionType.HAHA, now));
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target1, ReactionType.HAHA, now));

        // target2 reactions (should not affect target1)
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target2, ReactionType.SAD, now));

        Map<ReactionType, Long> counts = adapter.countReactionsByTargetGroupedByType(target1);
        assertThat(counts).containsEntry(ReactionType.LOVE, 2L)
                .containsEntry(ReactionType.FIRE, 1L)
                .containsEntry(ReactionType.HAHA, 3L)
                .containsEntry(ReactionType.SAD, 0L);

        long total = adapter.countTotalReactionsByTarget(target1);
        assertThat(total).isEqualTo(6L);
    }

    @Test
    @DisplayName("Should batch count reactions grouped by type for multiple target IDs")
    void shouldBatchCountReactionsForMultipleTargetIds() {
        UUID comment1 = UUID.randomUUID();
        UUID comment2 = UUID.randomUUID();
        UUID comment3 = UUID.randomUUID();
        Instant now = Instant.now();

        ReactionTarget target1 = ReactionTarget.comment(comment1);
        ReactionTarget target2 = ReactionTarget.comment(comment2);

        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target1, ReactionType.LOVE, now));
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target1, ReactionType.LOVE, now));
        adapter.save(Reaction.create(UUID.randomUUID(), UUID.randomUUID(), target2, ReactionType.FIRE, now));

        Map<UUID, Map<ReactionType, Long>> batchCounts = adapter.countReactionsByTargetIdsGroupedByType(
                ReactionTargetType.COMMENT,
                List.of(comment1, comment2, comment3)
        );

        assertThat(batchCounts).containsOnlyKeys(comment1, comment2, comment3);

        Map<ReactionType, Long> counts1 = batchCounts.get(comment1);
        assertThat(counts1).containsEntry(ReactionType.LOVE, 2L)
                .containsEntry(ReactionType.FIRE, 0L)
                .containsEntry(ReactionType.HAHA, 0L)
                .containsEntry(ReactionType.SAD, 0L);

        Map<ReactionType, Long> counts2 = batchCounts.get(comment2);
        assertThat(counts2).containsEntry(ReactionType.LOVE, 0L)
                .containsEntry(ReactionType.FIRE, 1L)
                .containsEntry(ReactionType.HAHA, 0L)
                .containsEntry(ReactionType.SAD, 0L);

        Map<ReactionType, Long> counts3 = batchCounts.get(comment3);
        assertThat(counts3).containsEntry(ReactionType.LOVE, 0L)
                .containsEntry(ReactionType.FIRE, 0L)
                .containsEntry(ReactionType.HAHA, 0L)
                .containsEntry(ReactionType.SAD, 0L);
    }

    @Test
    @DisplayName("Should batch find active reaction types for an authenticated user across multiple target IDs")
    void shouldBatchFindUserReactionsForTargetIds() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        UUID comment1 = UUID.randomUUID();
        UUID comment2 = UUID.randomUUID();
        UUID comment3 = UUID.randomUUID();
        Instant now = Instant.now();

        adapter.save(Reaction.create(UUID.randomUUID(), userId, ReactionTarget.comment(comment1), ReactionType.LOVE, now));
        adapter.save(Reaction.create(UUID.randomUUID(), userId, ReactionTarget.comment(comment2), ReactionType.HAHA, now));
        adapter.save(Reaction.create(UUID.randomUUID(), otherUserId, ReactionTarget.comment(comment3), ReactionType.FIRE, now));

        Map<UUID, ReactionType> userReactions = adapter.findUserReactionsForTargetIds(
                userId,
                ReactionTargetType.COMMENT,
                List.of(comment1, comment2, comment3)
        );

        assertThat(userReactions).hasSize(2)
                .containsEntry(comment1, ReactionType.LOVE)
                .containsEntry(comment2, ReactionType.HAHA)
                .doesNotContainKey(comment3);
    }
}
