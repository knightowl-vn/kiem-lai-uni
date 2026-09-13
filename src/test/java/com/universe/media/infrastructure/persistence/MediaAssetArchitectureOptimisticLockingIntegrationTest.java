package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import(MediaAssetPersistenceAdapter.class)
class MediaAssetArchitectureOptimisticLockingIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private MediaAssetRepositoryPort repositoryPort;

    @Autowired
    private SpringDataMediaAssetJpaRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID testAssetId;

    @AfterEach
    void cleanUp() {
        if (testAssetId != null && repository.existsById(testAssetId.toString())) {
            repository.deleteById(testAssetId.toString());
        }
    }

    @Test
    @DisplayName("Optimistic locking through MediaAssetRepositoryPort rejects concurrent stale transaction commit and preserves winner state")
    void shouldRejectConcurrentStaleTransactionThroughRepositoryPort() throws Exception {
        testAssetId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-12T12:00:00Z");

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // 1. Persist initial ACTIVE MediaAsset through MediaAssetRepositoryPort
        transactionTemplate.execute(status -> {
            MediaAsset initialAsset = MediaAsset.registerInitial(
                    testAssetId,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    now
            );
            return repositoryPort.save(initialAsset);
        });

        MediaAssetJpaEntity initialEntity = repository.findById(testAssetId.toString()).orElseThrow();
        Long initialVersion = initialEntity.getPersistenceVersion();
        assertThat(initialVersion).isNotNull();

        // 2. Coordinate two independent concurrent transactions A and B
        CountDownLatch bothLoadedLatch = new CountDownLatch(2);
        CountDownLatch txACommittedLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Transaction A (Winner)
            Future<Void> futureA = executor.submit(() -> {
                transactionTemplate.execute(status -> {
                    // Load through MediaAssetRepositoryPort
                    MediaAsset assetA = repositoryPort.findById(testAssetId).orElseThrow();

                    bothLoadedLatch.countDown();
                    awaitLatch(bothLoadedLatch);

                    // Perform mutation A: change visibility to PRIVATE
                    assetA.changeVisibility(MediaVisibility.PRIVATE, now.plusSeconds(5));
                    repositoryPort.save(assetA);
                    return null;
                });
                txACommittedLatch.countDown();
                return null;
            });

            // Transaction B (Stale / Loser)
            Future<Void> futureB = executor.submit(() -> {
                transactionTemplate.execute(status -> {
                    // Load through MediaAssetRepositoryPort before Tx A commits
                    MediaAsset assetB = repositoryPort.findById(testAssetId).orElseThrow();

                    bothLoadedLatch.countDown();
                    awaitLatch(bothLoadedLatch);

                    // Perform mutation B: archive asset
                    assetB.archive(now.plusSeconds(10));
                    repositoryPort.save(assetB);

                    // Await Tx A's successful commit before attempting Tx B's commit
                    awaitLatch(txACommittedLatch);

                    return null;
                });
                return null;
            });

            // 3. Transaction A must commit successfully
            futureA.get(10, TimeUnit.SECONDS);

            // 4. Transaction B must fail with Spring OptimisticLockingFailureException
            assertThatThrownBy(() -> {
                try {
                    futureB.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(OptimisticLockingFailureException.class);

            // 5. Verify Transaction A's state remains persisted through MediaAssetRepositoryPort
            MediaAsset persistedDomain = repositoryPort.findById(testAssetId).orElseThrow();
            assertThat(persistedDomain.getVisibility()).isEqualTo(MediaVisibility.PRIVATE);
            assertThat(persistedDomain.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
            assertThat(persistedDomain.isArchived()).isFalse();

            // 6. Verify database row state: version advanced and stale Tx B mutation was not applied
            MediaAssetJpaEntity persistedEntity = repository.findById(testAssetId.toString()).orElseThrow();
            assertThat(persistedEntity.getVisibility()).isEqualTo("PRIVATE");
            assertThat(persistedEntity.getStatus()).isEqualTo("ACTIVE");
            assertThat(persistedEntity.getPersistenceVersion()).isEqualTo(initialVersion + 1);

        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for synchronization latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for synchronization latch", e);
        }
    }
}
