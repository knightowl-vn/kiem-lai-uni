package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.exceptions.WikiCoverMediaAssetDeletingException;
import com.universe.wiki.application.orphan.WikiCoverOrphanReconciliationResult;
import com.universe.wiki.application.orphan.WikiCoverOrphanReconciliationService;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import com.universe.wiki.infrastructure.maintenance.WikiCoverOrphanProperties;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        WikiCoverOrphanPersistenceAdapter.class,
        WikiCoverOrphanDestructiveRaceIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Orphan Destructive Race Integration Tests (MySQL)")
class WikiCoverOrphanDestructiveRaceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public MediaContract mediaContract() {
            return mock(MediaContract.class);
        }

        @Bean
        public WikiArticleRepositoryPort articleRepositoryPort() {
            return mock(WikiArticleRepositoryPort.class);
        }

        @Bean
        public ClockPort clockPort() {
            return mock(ClockPort.class);
        }
    }

    @Autowired
    private SpringDataWikiCoverOrphanJpaRepository jpaRepository;

    @Autowired
    private WikiCoverOrphanPersistenceAdapter orphanAdapter;

    @Autowired
    private MediaContract mediaContract;

    @Autowired
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Autowired
    private ClockPort clockPort;

    private WikiCoverOrphanProperties properties;
    private WikiCoverOrphanReconciliationService reconciliationService;

    private final List<UUID> createdAssetIds = new ArrayList<>();
    private final Instant baseNow = Instant.parse("2026-09-24T10:00:00Z");

    @BeforeEach
    void setUp() {
        properties = new WikiCoverOrphanProperties(
                true,
                "0 0 2 * * *",
                "0 0 3 * * *",
                "Asia/Ho_Chi_Minh",
                300L,
                100,
                30L,   // 30 days orphan grace
                300L,  // 300 seconds lease duration
                10
        );

        reconciliationService = new WikiCoverOrphanReconciliationService(
                orphanAdapter,
                articleRepositoryPort,
                mediaContract,
                clockPort,
                properties
        );
    }

    @AfterEach
    void tearDown() {
        for (UUID id : createdAssetIds) {
            jpaRepository.deleteById(id.toString());
        }
        createdAssetIds.clear();
    }

    @Test
    @DisplayName("Durable DELETING Fence: Stale PROCESSING recovery does NOT revoke DELETING fence while paused in pre-delete hook")
    void shouldNotRecoverDeletingRowsDuringStaleLeaseRecovery() throws Exception {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        Instant firstSeen = baseNow.minus(Duration.ofDays(40));
        orphanAdapter.recordOrphanObservation(assetId, firstSeen);

        when(clockPort.now()).thenReturn(baseNow);
        when(articleRepositoryPort.hasCoverReference(assetId)).thenReturn(false);

        CountDownLatch pausedAtPreDeleteLatch = new CountDownLatch(1);
        CountDownLatch resumeWorkerLatch = new CountDownLatch(1);
        AtomicBoolean hookExecuted = new AtomicBoolean(false);

        reconciliationService.setPreDeleteHook((claimedAssetId, token) -> {
            hookExecuted.set(true);
            pausedAtPreDeleteLatch.countDown();
            try {
                boolean unblocked = resumeWorkerLatch.await(10, TimeUnit.SECONDS);
                if (!unblocked) {
                    throw new RuntimeException("Timed out waiting for recovery thread to complete");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WikiCoverOrphanReconciliationResult> workerFuture = executor.submit(() ->
                    reconciliationService.reconcileOrphans()
            );

            boolean reachedHook = pausedAtPreDeleteLatch.await(10, TimeUnit.SECONDS);
            assertThat(reachedHook).isTrue();
            assertThat(hookExecuted.get()).isTrue();

            // CRITICAL VERIFICATION: In DB, status has transitioned to DELETING
            Optional<WikiCoverOrphanRecord> midState = orphanAdapter.findByMediaAssetId(assetId);
            assertThat(midState).isPresent();
            assertThat(midState.get().status()).isEqualTo(WikiCoverOrphanStatus.DELETING);
            UUID workerToken = midState.get().claimToken();
            assertThat(workerToken).isNotNull();

            // Attempt stale lease recovery: lease has expired for PROCESSING, recoverStaleProcessing runs
            Instant futureNow = baseNow.plus(Duration.ofMinutes(10));
            int recovered = orphanAdapter.recoverStaleProcessing(futureNow, futureNow);
            // CRITICAL PROOF: recoverStaleProcessing MUST return 0 because row is DELETING!
            assertThat(recovered).isEqualTo(0);

            // Verify row remains DELETING with unchanged claim token
            Optional<WikiCoverOrphanRecord> stateAfterRecovery = orphanAdapter.findByMediaAssetId(assetId);
            assertThat(stateAfterRecovery).isPresent();
            assertThat(stateAfterRecovery.get().status()).isEqualTo(WikiCoverOrphanStatus.DELETING);
            assertThat(stateAfterRecovery.get().claimToken()).isEqualTo(workerToken);

            // Concurrent attachment attempt via Reference-Attach Gate MUST fail
            assertThatThrownBy(() -> orphanAdapter.coordinateCoverAttachment(assetId))
                    .isInstanceOf(WikiCoverMediaAssetDeletingException.class);

            // Now unblock worker to proceed with Media delete
            resumeWorkerLatch.countDown();

            WikiCoverOrphanReconciliationResult result = workerFuture.get(10, TimeUnit.SECONDS);

            // Worker completed delete successfully
            assertThat(result.eligibleClaimed()).isEqualTo(1);
            assertThat(result.successfullyDeleted()).isEqualTo(1);

            // Media contract delete was invoked
            verify(mediaContract).delete(assetId);

            // Row is deleted from DB upon success
            Optional<WikiCoverOrphanRecord> finalState = orphanAdapter.findByMediaAssetId(assetId);
            assertThat(finalState).isEmpty();

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Abort Media Delete: If claim ownership was lost prior to prepareDeletionFence, worker aborts and never deletes Media")
    void shouldAbortMediaDeleteIfOwnershipLostPriorToDeletionFence() {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        Instant firstSeen = baseNow.minus(Duration.ofDays(40));
        orphanAdapter.recordOrphanObservation(assetId, firstSeen);

        when(clockPort.now()).thenReturn(baseNow);
        when(articleRepositoryPort.hasCoverReference(assetId)).thenReturn(false);

        // Claim with token A
        UUID tokenA = UUID.randomUUID();
        boolean claimed = orphanAdapter.claimIfEligible(assetId, baseNow, tokenA, baseNow);
        assertThat(claimed).isTrue();

        // Simulate lease recovery revoking token A while still PROCESSING
        Instant futureNow = baseNow.plus(Duration.ofMinutes(10));
        int recovered = orphanAdapter.recoverStaleProcessing(futureNow, futureNow);
        assertThat(recovered).isEqualTo(1);

        // Now worker with token A calls prepareDeletionFence
        WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult result =
                orphanAdapter.prepareDeletionFence(assetId, tokenA, futureNow);

        assertThat(result).isEqualTo(WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult.LOST_OWNERSHIP);

        // Verify media delete was never called
        verify(mediaContract, never()).delete(assetId);
    }
}
