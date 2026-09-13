package com.universe.media.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
class MediaAssetJpaOptimisticLockingIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private SpringDataMediaAssetJpaRepository repository;

    private String testAssetId;

    @AfterEach
    void cleanUp() {
        if (testAssetId != null && repository.existsById(testAssetId)) {
            repository.deleteById(testAssetId);
        }
    }

    @Test
    @DisplayName("Hibernate @Version rejects stale detached MediaAsset and advances persistence version on success")
    void shouldRejectStaleDetachedMediaAssetWithOptimisticLock() {
        testAssetId = UUID.randomUUID().toString();

        // 1. Persist initial MediaAsset
        MediaAssetJpaEntity initialEntity = createMediaAssetEntity(testAssetId);
        MediaAssetJpaEntity inserted = repository.saveAndFlush(initialEntity);

        Long initialPersistenceVersion = inserted.getPersistenceVersion();
        assertThat(initialPersistenceVersion).isNotNull();

        // 2. Load the same row independently twice into two detached snapshots
        MediaAssetJpaEntity staleEntity = repository.findById(testAssetId).orElseThrow();
        MediaAssetJpaEntity winningEntity = repository.findById(testAssetId).orElseThrow();

        assertThat(staleEntity.getPersistenceVersion()).isEqualTo(initialPersistenceVersion);
        assertThat(winningEntity.getPersistenceVersion()).isEqualTo(initialPersistenceVersion);

        // 3. Commit update from Transaction A (winner)
        winningEntity.setVisibility("PRIVATE");
        winningEntity.setStatus("ARCHIVED");
        winningEntity.setCurrentVersionNumber(2);
        winningEntity.setUpdatedAt(Instant.now());

        MediaAssetJpaEntity savedWinner = repository.saveAndFlush(winningEntity);

        // Verify persistence version advanced after successful update
        assertThat(savedWinner.getPersistenceVersion()).isGreaterThan(initialPersistenceVersion);

        // 4 & 5. Attempt to commit update from stale Transaction B -> rejected via optimistic locking
        staleEntity.setVisibility("RESTRICTED");
        staleEntity.setStatus("DELETED");
        staleEntity.setCurrentVersionNumber(3);
        staleEntity.setUpdatedAt(Instant.now());

        assertThatThrownBy(() -> repository.saveAndFlush(staleEntity))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // 6. Verify successful update remains persisted in database
        MediaAssetJpaEntity persisted = repository.findById(testAssetId).orElseThrow();
        assertThat(persisted.getVisibility()).isEqualTo("PRIVATE");
        assertThat(persisted.getStatus()).isEqualTo("ARCHIVED");
        assertThat(persisted.getCurrentVersionNumber()).isEqualTo(2);
        assertThat(persisted.getPersistenceVersion()).isEqualTo(savedWinner.getPersistenceVersion());
    }

    private MediaAssetJpaEntity createMediaAssetEntity(String id) {
        Instant now = Instant.now();

        MediaAssetJpaEntity entity = new MediaAssetJpaEntity();
        entity.setId(id);
        entity.setMediaType("IMAGE");
        entity.setVisibility("PUBLIC");
        entity.setStatus("ACTIVE");
        entity.setCurrentVersionNumber(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
