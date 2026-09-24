package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort.ClearReferencedOrphanResult;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        WikiCoverOrphanPersistenceAdapter.class,
        WikiCoverOrphanDiscoveryPreservationIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Orphan Discovery Preservation Integration Tests (MySQL)")
class WikiCoverOrphanDiscoveryPreservationIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public com.universe.wiki.application.ports.WikiArticleRepositoryPort articleRepositoryPort() {
            return mock(com.universe.wiki.application.ports.WikiArticleRepositoryPort.class);
        }
    }

    @Autowired
    private SpringDataWikiCoverOrphanJpaRepository jpaRepository;

    @Autowired
    private WikiCoverOrphanPersistenceAdapter orphanAdapter;

    private final List<UUID> createdAssetIds = new ArrayList<>();
    private final Instant baseNow = Instant.parse("2026-09-24T12:00:00Z");

    @AfterEach
    void tearDown() {
        for (UUID id : createdAssetIds) {
            jpaRepository.deleteById(id.toString());
        }
        createdAssetIds.clear();
    }

    @Test
    @DisplayName("Test A: referenced + PENDING -> orphan cleared")
    void testA_referencedPlusPending_shouldClearOrphan() {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        orphanAdapter.recordOrphanObservation(assetId, baseNow);
        assertThat(orphanAdapter.findByMediaAssetId(assetId)).isPresent();
        assertThat(orphanAdapter.findByMediaAssetId(assetId).get().status()).isEqualTo(WikiCoverOrphanStatus.PENDING);

        ClearReferencedOrphanResult result = orphanAdapter.clearNonDeletingOrphanForReference(assetId);
        assertThat(result).isEqualTo(ClearReferencedOrphanResult.CLEARED);

        assertThat(orphanAdapter.findByMediaAssetId(assetId)).isEmpty();
    }

    @Test
    @DisplayName("Test B: referenced + PROCESSING -> orphan cleared")
    void testB_referencedPlusProcessing_shouldClearOrphan() {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        Instant firstSeen = baseNow.minus(Duration.ofDays(35));
        orphanAdapter.recordOrphanObservation(assetId, firstSeen);

        UUID claimToken = UUID.randomUUID();
        boolean claimed = orphanAdapter.claimIfEligible(assetId, baseNow, claimToken, baseNow);
        assertThat(claimed).isTrue();
        assertThat(orphanAdapter.findByMediaAssetId(assetId).get().status()).isEqualTo(WikiCoverOrphanStatus.PROCESSING);

        ClearReferencedOrphanResult result = orphanAdapter.clearNonDeletingOrphanForReference(assetId);
        assertThat(result).isEqualTo(ClearReferencedOrphanResult.CLEARED);

        assertThat(orphanAdapter.findByMediaAssetId(assetId)).isEmpty();
    }

    @Test
    @DisplayName("Test C: referenced + DELETING -> DELETING row remains completely unchanged")
    void testC_referencedPlusDeleting_shouldPreserveDeletingRowUnchanged() {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        Instant firstSeen = baseNow.minus(Duration.ofDays(35));
        orphanAdapter.recordOrphanObservation(assetId, firstSeen);

        UUID claimToken = UUID.randomUUID();
        boolean claimed = orphanAdapter.claimIfEligible(assetId, baseNow, claimToken, baseNow);
        assertThat(claimed).isTrue();

        // Worker transitions to DELETING fence
        WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult fenceResult =
                orphanAdapter.prepareDeletionFence(assetId, claimToken, baseNow);
        assertThat(fenceResult).isEqualTo(WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult.FENCED_FOR_DELETION);

        WikiCoverOrphanRecord before = orphanAdapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(before.status()).isEqualTo(WikiCoverOrphanStatus.DELETING);

        // Discovery observes referenced asset
        ClearReferencedOrphanResult clearResult = orphanAdapter.clearNonDeletingOrphanForReference(assetId);
        assertThat(clearResult).isEqualTo(ClearReferencedOrphanResult.DELETING_PRESERVED);

        // Assert all fields remain exactly unchanged
        WikiCoverOrphanRecord after = orphanAdapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(after.status()).isEqualTo(WikiCoverOrphanStatus.DELETING);
        assertThat(after.claimToken()).isEqualTo(claimToken);
        assertThat(after.lockedAt()).isEqualTo(before.lockedAt());
        assertThat(after.retryCount()).isEqualTo(before.retryCount());
        assertThat(after.firstSeenOrphanAt()).isEqualTo(firstSeen);
    }

    @Test
    @DisplayName("Test D: DELETING observation through recordOrphanObservation -> remains DELETING unchanged")
    void testD_deletingObservationThroughRecordOrphanObservation_shouldRemainDeletingUnchanged() {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        Instant firstSeen = baseNow.minus(Duration.ofDays(35));
        orphanAdapter.recordOrphanObservation(assetId, firstSeen);

        UUID claimToken = UUID.randomUUID();
        orphanAdapter.claimIfEligible(assetId, baseNow, claimToken, baseNow);
        orphanAdapter.prepareDeletionFence(assetId, claimToken, baseNow);

        WikiCoverOrphanRecord before = orphanAdapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(before.status()).isEqualTo(WikiCoverOrphanStatus.DELETING);

        // Discovery or detachment invokes recordOrphanObservation on the same asset
        orphanAdapter.recordOrphanObservation(assetId, baseNow.plus(Duration.ofHours(1)));

        // Assert row remains DELETING and was not reset or overwritten
        WikiCoverOrphanRecord after = orphanAdapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(after.status()).isEqualTo(WikiCoverOrphanStatus.DELETING);
        assertThat(after.claimToken()).isEqualTo(claimToken);
        assertThat(after.lockedAt()).isEqualTo(before.lockedAt());
        assertThat(after.retryCount()).isEqualTo(before.retryCount());
        assertThat(after.firstSeenOrphanAt()).isEqualTo(firstSeen);
    }
}
