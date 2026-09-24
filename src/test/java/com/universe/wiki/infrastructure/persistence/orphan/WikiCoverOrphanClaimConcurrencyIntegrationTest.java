package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        WikiCoverOrphanPersistenceAdapter.class
})
@DisplayName("Wiki Cover Orphan Claim Concurrency Integration Tests")
class WikiCoverOrphanClaimConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private SpringDataWikiCoverOrphanJpaRepository jpaRepository;

    @Autowired
    private WikiCoverOrphanPersistenceAdapter adapter;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : createdAssetIds) {
            jpaRepository.deleteById(id.toString());
        }
        createdAssetIds.clear();
    }

    @Test
    @DisplayName("Section 21: Concurrent claims for the same eligible PENDING row produce exactly one winner")
    void shouldProduceExactlyOneWinnerUnderConcurrentClaims() throws Exception {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        Instant cutoff = Instant.parse("2026-09-01T12:00:00Z");
        Instant now = Instant.parse("2026-09-01T12:05:00Z");

        // Seed eligible PENDING row
        adapter.recordOrphanObservation(assetId, t1);

        UUID tokenA = UUID.randomUUID();
        UUID tokenB = UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Future<Boolean> futureA = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return adapter.claimIfEligible(assetId, cutoff, tokenA, now);
            });

            Future<Boolean> futureB = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return adapter.claimIfEligible(assetId, cutoff, tokenB, now);
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            boolean resultA = futureA.get(10, TimeUnit.SECONDS);
            boolean resultB = futureB.get(10, TimeUnit.SECONDS);

            // Exactly one winner, exactly one loser
            assertThat(resultA ^ resultB).as("Exactly one claim must succeed").isTrue();

            UUID expectedWinner = resultA ? tokenA : tokenB;

            WikiCoverOrphanRecord record = adapter.findByMediaAssetId(assetId).orElseThrow();
            assertThat(record.status()).isEqualTo(WikiCoverOrphanStatus.PROCESSING);
            assertThat(record.claimToken()).isEqualTo(expectedWinner);
            assertThat(record.lockedAt()).isEqualTo(now);
        } finally {
            executor.shutdownNow();
        }
    }
}
