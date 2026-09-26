package com.universe.interaction.application.mutation;

import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceMapper;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        SetReactionUseCase.class,
        RemoveReactionUseCase.class,
        ReactionMutationConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("Reaction Mutation Concurrency & Real DB Transaction Recovery Integration Tests")
class ReactionMutationConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SetReactionUseCase setReactionUseCase;

    @Autowired
    private RemoveReactionUseCase removeReactionUseCase;

    @Autowired
    private TestConcurrentReactionRepositoryDecorator testDecorator;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        cleanData();
        executor = Executors.newFixedThreadPool(4);
        testDecorator.reset();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.execute("DELETE FROM interaction_reactions;");
    }

    // =========================================================================
    // SCENARIO 1 — REAL MYSQL FIRST-INSERT RACE & RECOVERY PROOF
    // =========================================================================

    @Test
    @DisplayName("Should deterministically recover from real MySQL UNIQUE collision without UnexpectedRollbackException and converge to exactly 1 row")
    void shouldRecoverFromRealDatabaseUniqueCollisionWithoutUnexpectedRollbackException() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(chapterId);

        // Force both threads to see empty on initial lookup before either performs INSERT
        testDecorator.enableRaceSimulation(2);

        SetReactionCommand command1 = new SetReactionCommand(userId, target, ReactionType.LOVE);
        SetReactionCommand command2 = new SetReactionCommand(userId, target, ReactionType.FIRE);

        Future<Reaction> future1 = executor.submit(() -> setReactionUseCase.execute(command1));
        Future<Reaction> future2 = executor.submit(() -> setReactionUseCase.execute(command2));

        // Wait until both threads completed findByUserAndTarget and observed Optional.empty()
        boolean bothSawEmpty = testDecorator.bothObservedEmpty.await(5, TimeUnit.SECONDS);
        assertThat(bothSawEmpty)
                .as("Both threads must observe empty on initial lookup before save")
                .isTrue();
        assertThat(testDecorator.findEmptyCount.get()).isEqualTo(2);

        // Release both threads to perform concurrent saveAndFlush against real MySQL
        testDecorator.releaseInserts.countDown();

        // Both futures must complete successfully without UnexpectedRollbackException or unhandled errors
        Reaction result1 = future1.get(10, TimeUnit.SECONDS);
        Reaction result2 = future2.get(10, TimeUnit.SECONDS);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();

        // 1. Proof of collision and recovery:
        // Initial save: 2 threads call save() -> 1 succeeds, 1 collides with DuplicateReactionException
        // Losing thread recovers -> refetches authoritative row -> updates to its desired type -> calls save() again
        // Total save() invocations = 3
        assertThat(testDecorator.saveCallCount.get())
                .as("Must prove initial collision occurred and recovery executed save() in clean transaction")
                .isEqualTo(3);

        // 2. Authoritative Database Invariant: exactly 1 row exists for user + target
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reactions WHERE user_id = ? AND target_type = 'NOVEL_CHAPTER' AND target_id = ?",
                Integer.class,
                userId.toString(),
                chapterId.toString()
        );
        assertThat(rowCount).isEqualTo(1);

        // 3. Stored reaction is valid under last-write-wins (the recovered update to FIRE)
        String storedReactionType = jdbcTemplate.queryForObject(
                "SELECT reaction_type FROM interaction_reactions WHERE user_id = ? AND target_type = 'NOVEL_CHAPTER' AND target_id = ?",
                String.class,
                userId.toString(),
                chapterId.toString()
        );
        assertThat(storedReactionType).isIn("LOVE", "FIRE");
    }

    // =========================================================================
    // SCENARIO 2 — CONCURRENT MUTATIONS FROM DIFFERENT USERS
    // =========================================================================

    @Test
    @DisplayName("Should handle concurrent reactions from different users independently with exact count = 2")
    void shouldHandleConcurrentReactionsFromDifferentUsersIndependently() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(chapterId);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Reaction> future1 = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return setReactionUseCase.execute(new SetReactionCommand(userA, target, ReactionType.LOVE));
        });

        Future<Reaction> future2 = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return setReactionUseCase.execute(new SetReactionCommand(userB, target, ReactionType.HAHA));
        });

        boolean bothReady = readyLatch.await(5, TimeUnit.SECONDS);
        assertThat(bothReady).isTrue();
        startLatch.countDown();

        Reaction result1 = future1.get(10, TimeUnit.SECONDS);
        Reaction result2 = future2.get(10, TimeUnit.SECONDS);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'NOVEL_CHAPTER' AND target_id = ?",
                Integer.class,
                chapterId.toString()
        );
        assertThat(rowCount).isEqualTo(2);
    }

    // =========================================================================
    // SCENARIO 3 — IDEMPOTENT REMOVE CONCURRENCY
    // =========================================================================

    @Test
    @DisplayName("Should handle concurrent remove requests idempotently")
    void shouldHandleConcurrentRemoveRequestsIdempotently() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(chapterId);

        // Pre-insert reaction
        setReactionUseCase.execute(new SetReactionCommand(userId, target, ReactionType.FIRE));

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Boolean> future1 = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return removeReactionUseCase.execute(new RemoveReactionCommand(userId, target));
        });

        Future<Boolean> future2 = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return removeReactionUseCase.execute(new RemoveReactionCommand(userId, target));
        });

        boolean bothReady = readyLatch.await(5, TimeUnit.SECONDS);
        assertThat(bothReady).isTrue();
        startLatch.countDown();

        boolean rem1 = future1.get(10, TimeUnit.SECONDS);
        boolean rem2 = future2.get(10, TimeUnit.SECONDS);

        // One must be true, one false (or both false if already removed), exactly 1 was deleted
        assertThat(rem1 || rem2).isTrue();
        assertThat(rem1 && rem2).isFalse();

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_reactions WHERE user_id = ? AND target_type = 'NOVEL_CHAPTER' AND target_id = ?",
                Integer.class,
                userId.toString(),
                chapterId.toString()
        );
        assertThat(rowCount).isZero();
    }

    // =========================================================================
    // TEST CONFIGURATION & CONCURRENT REPOSITORY DECORATOR
    // =========================================================================

    @TestConfiguration
    static class TestConfig {

        @Bean
        public ReactionTargetEligibilityPort reactionTargetEligibilityPort() {
            return target -> true; // Eligible for integration test purposes
        }

        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public IdGeneratorPort idGeneratorPort() {
            return UUID::randomUUID;
        }

        @Bean
        @Primary
        public ReactionRepositoryPort testConcurrentReactionRepository(
                ReactionPersistenceAdapter realAdapter
        ) {
            return new TestConcurrentReactionRepositoryDecorator(realAdapter);
        }
    }

    static class TestConcurrentReactionRepositoryDecorator implements ReactionRepositoryPort {

        private final ReactionPersistenceAdapter delegate;
        final AtomicBoolean raceSimulationActive = new AtomicBoolean(false);
        final AtomicInteger findEmptyCount = new AtomicInteger(0);
        final AtomicInteger saveCallCount = new AtomicInteger(0);
        volatile CountDownLatch bothObservedEmpty = new CountDownLatch(2);
        volatile CountDownLatch releaseInserts = new CountDownLatch(1);

        TestConcurrentReactionRepositoryDecorator(ReactionPersistenceAdapter delegate) {
            this.delegate = delegate;
        }

        void enableRaceSimulation(int expectedThreads) {
            this.raceSimulationActive.set(true);
            this.findEmptyCount.set(0);
            this.saveCallCount.set(0);
            this.bothObservedEmpty = new CountDownLatch(expectedThreads);
            this.releaseInserts = new CountDownLatch(1);
        }

        void reset() {
            this.raceSimulationActive.set(false);
            this.findEmptyCount.set(0);
            this.saveCallCount.set(0);
        }

        @Override
        public Optional<Reaction> findByUserAndTarget(UUID userId, ReactionTarget target) {
            Optional<Reaction> result = delegate.findByUserAndTarget(userId, target);
            if (raceSimulationActive.get() && result.isEmpty()) {
                findEmptyCount.incrementAndGet();
                bothObservedEmpty.countDown();
                try {
                    bothObservedEmpty.await(5, TimeUnit.SECONDS);
                    releaseInserts.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }

        @Override
        public Reaction save(Reaction reaction) {
            saveCallCount.incrementAndGet();
            return delegate.save(reaction);
        }

        @Override
        public Optional<Reaction> findById(UUID reactionId) {
            return delegate.findById(reactionId);
        }

        @Override
        public Optional<ReactionType> findUserReactionType(UUID userId, ReactionTarget target) {
            return delegate.findUserReactionType(userId, target);
        }

        @Override
        public void delete(Reaction reaction) {
            delegate.delete(reaction);
        }

        @Override
        public boolean deleteByUserAndTarget(UUID userId, ReactionTarget target) {
            return delegate.deleteByUserAndTarget(userId, target);
        }

        @Override
        public java.util.Map<ReactionType, Long> countReactionsByTargetGroupedByType(ReactionTarget target) {
            return delegate.countReactionsByTargetGroupedByType(target);
        }

        @Override
        public java.util.Map<UUID, java.util.Map<ReactionType, Long>> countReactionsByTargetIdsGroupedByType(
                com.universe.interaction.domain.reaction.ReactionTargetType targetType,
                java.util.Collection<UUID> targetIds
        ) {
            return delegate.countReactionsByTargetIdsGroupedByType(targetType, targetIds);
        }

        @Override
        public java.util.Map<UUID, ReactionType> findUserReactionsForTargetIds(
                UUID userId,
                com.universe.interaction.domain.reaction.ReactionTargetType targetType,
                java.util.Collection<UUID> targetIds
        ) {
            return delegate.findUserReactionsForTargetIds(userId, targetType, targetIds);
        }

        @Override
        public long countTotalReactionsByTarget(ReactionTarget target) {
            return delegate.countTotalReactionsByTarget(target);
        }

        @Override
        public void deleteAllByTargetIds(
                com.universe.interaction.domain.reaction.ReactionTargetType targetType,
                java.util.Collection<UUID> targetIds
        ) {
            delegate.deleteAllByTargetIds(targetType, targetIds);
        }
    }
}
