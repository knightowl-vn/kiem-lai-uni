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
@DisplayName("Wiki Cover Orphan Observation Concurrency Integration Tests")
class WikiCoverOrphanObservationConcurrencyIntegrationTest {

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
    @DisplayName("Section 22: Concurrent recordOrphanObservation calls for same asset complete safely with one stable epoch")
    void shouldHandleConcurrentOrphanObservationsSafely() throws Exception {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Future<?> futureA = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                adapter.recordOrphanObservation(assetId, t1);
                return null;
            });

            Future<?> futureB = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                adapter.recordOrphanObservation(assetId, t1);
                return null;
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            futureA.get(10, TimeUnit.SECONDS);
            futureB.get(10, TimeUnit.SECONDS);

            // Exactly one row exists
            WikiCoverOrphanRecord record = adapter.findByMediaAssetId(assetId).orElseThrow();
            assertThat(record.mediaAssetId()).isEqualTo(assetId);
            assertThat(record.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
            assertThat(record.firstSeenOrphanAt()).isEqualTo(t1);

            // Follow-up duplicate observation at T2 preserves T1
            Instant t2 = Instant.parse("2026-09-05T12:00:00Z");
            adapter.recordOrphanObservation(assetId, t2);

            WikiCoverOrphanRecord recordAfterT2 = adapter.findByMediaAssetId(assetId).orElseThrow();
            assertThat(recordAfterT2.firstSeenOrphanAt()).isEqualTo(t1);
        } finally {
            executor.shutdownNow();
        }
    }
}
