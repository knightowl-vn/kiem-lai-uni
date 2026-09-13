package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.exceptions.NarrationMediaCleanupTaskAlreadyExistsException;
import com.universe.novel.application.ports.NarrationMediaCleanupTaskRepositoryPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import com.universe.test.TestDatabaseSupport;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NarrationMediaCleanupTaskPersistenceAdapter.class)
@DisplayName("NarrationMediaCleanupTask JPA Persistence Integration Tests (MySQL)")
class NarrationMediaCleanupTaskJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private NarrationMediaCleanupTaskRepositoryPort repositoryPort;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM novel_narration_media_cleanup_tasks");
    }

    @Test
    @DisplayName("Should save, find by id and find by mediaAssetId")
    void shouldSaveAndFindTask() {
        UUID taskId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                taskId,
                mediaAssetId,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                now
        );

        repositoryPort.save(task);
        entityManager.clear();

        Optional<NarrationMediaCleanupTask> byId = repositoryPort.findById(taskId);
        assertThat(byId).isPresent();
        assertThat(byId.get().getId()).isEqualTo(taskId);
        assertThat(byId.get().getMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(byId.get().getReason()).isEqualTo(NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        assertThat(byId.get().getAttemptCount()).isEqualTo(0);
        assertThat(byId.get().getLastErrorType()).isNull();
        assertThat(byId.get().getLastAttemptAt()).isNull();
        assertThat(byId.get().getCreatedAt()).isEqualTo(now);
        assertThat(byId.get().getUpdatedAt()).isEqualTo(now);

        Optional<NarrationMediaCleanupTask> byMediaAsset = repositoryPort.findByMediaAssetId(mediaAssetId);
        assertThat(byMediaAsset).isPresent();
        assertThat(byMediaAsset.get().getId()).isEqualTo(taskId);
    }

    @Test
    @DisplayName("Should update failed attempt and persist mutated fields")
    void shouldUpdateFailedAttemptAndPersist() {
        UUID taskId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                taskId,
                mediaAssetId,
                NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET,
                now
        );
        repositoryPort.save(task);
        entityManager.clear();

        NarrationMediaCleanupTask loaded = repositoryPort.findById(taskId).orElseThrow();
        Instant attemptTime = now.plusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        loaded.recordFailedAttempt("StorageException", attemptTime);

        repositoryPort.save(loaded);
        entityManager.clear();

        NarrationMediaCleanupTask updated = repositoryPort.findById(taskId).orElseThrow();
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getLastErrorType()).isEqualTo("StorageException");
        assertThat(updated.getLastAttemptAt()).isEqualTo(attemptTime);
        assertThat(updated.getUpdatedAt()).isEqualTo(attemptTime);
        assertThat(updated.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Should find oldest cleanup tasks ordered by createdAt ASC, id ASC bounded by limit")
    void shouldFindOldestTasksWithLimitAndOrdering() {
        Instant t1 = Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().minusSeconds(50).truncatedTo(ChronoUnit.MICROS);
        Instant t3 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        NarrationMediaCleanupTask task1 = NarrationMediaCleanupTask.create(id1, UUID.randomUUID(), NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, t1);
        NarrationMediaCleanupTask task2 = NarrationMediaCleanupTask.create(id2, UUID.randomUUID(), NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, t2);
        NarrationMediaCleanupTask task3 = NarrationMediaCleanupTask.create(id3, UUID.randomUUID(), NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET, t3);

        // Save out of order
        repositoryPort.save(task3);
        repositoryPort.save(task1);
        repositoryPort.save(task2);
        entityManager.clear();

        List<NarrationMediaCleanupTask> oldestTwo = repositoryPort.findOldest(2);
        assertThat(oldestTwo).hasSize(2);
        assertThat(oldestTwo.get(0).getId()).isEqualTo(id1);
        assertThat(oldestTwo.get(1).getId()).isEqualTo(id2);

        List<NarrationMediaCleanupTask> allThree = repositoryPort.findOldest(10);
        assertThat(allThree).hasSize(3);
        assertThat(allThree.get(0).getId()).isEqualTo(id1);
        assertThat(allThree.get(1).getId()).isEqualTo(id2);
        assertThat(allThree.get(2).getId()).isEqualTo(id3);
    }

    @Test
    @DisplayName("Should deterministically tie-break by id ASC when tasks have identical createdAt")
    void shouldDeterministicallyTieBreakByIdWhenCreatedAtIdentical() {
        Instant sameCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        UUID idAlpha = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID idBeta = UUID.fromString("00000000-0000-0000-0000-000000000020");

        NarrationMediaCleanupTask taskBeta = NarrationMediaCleanupTask.create(
                idBeta, UUID.randomUUID(), NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET, sameCreatedAt
        );
        NarrationMediaCleanupTask taskAlpha = NarrationMediaCleanupTask.create(
                idAlpha, UUID.randomUUID(), NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, sameCreatedAt
        );

        // Insert in reverse ID order (Beta then Alpha)
        repositoryPort.save(taskBeta);
        repositoryPort.save(taskAlpha);
        entityManager.clear();

        List<NarrationMediaCleanupTask> results = repositoryPort.findOldest(10);
        assertThat(results).hasSize(2);
        // Alpha must come before Beta because 0000...0010 < 0000...0020
        assertThat(results.get(0).getId()).isEqualTo(idAlpha);
        assertThat(results.get(1).getId()).isEqualTo(idBeta);
    }

    @Test
    @DisplayName("Should delete task by id and by mediaAssetId")
    void shouldDeleteTasks() {
        UUID taskId1 = UUID.randomUUID();
        UUID mediaAssetId1 = UUID.randomUUID();
        UUID taskId2 = UUID.randomUUID();
        UUID mediaAssetId2 = UUID.randomUUID();

        repositoryPort.save(NarrationMediaCleanupTask.create(taskId1, mediaAssetId1, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, Instant.now()));
        repositoryPort.save(NarrationMediaCleanupTask.create(taskId2, mediaAssetId2, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET, Instant.now()));
        entityManager.clear();

        // 1. Delete by ID
        repositoryPort.deleteById(taskId1);
        entityManager.clear();
        assertThat(repositoryPort.findById(taskId1)).isEmpty();
        assertThat(repositoryPort.findById(taskId2)).isPresent();

        // 2. Delete by MediaAssetId
        repositoryPort.deleteByMediaAssetId(mediaAssetId2);
        entityManager.clear();
        assertThat(repositoryPort.findById(taskId2)).isEmpty();
    }

    @Test
    @DisplayName("Should translate uq_novel_narration_media_cleanup_tasks_media_asset into NarrationMediaCleanupTaskAlreadyExistsException on real MySQL")
    void shouldEnforceUniqueConstraintOnMediaAssetId() {
        UUID mediaAssetId = UUID.randomUUID();
        UUID taskId1 = UUID.randomUUID();
        UUID taskId2 = UUID.randomUUID();

        repositoryPort.save(NarrationMediaCleanupTask.create(taskId1, mediaAssetId, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, Instant.now()));
        entityManager.clear();

        assertThatThrownBy(() -> {
            repositoryPort.save(NarrationMediaCleanupTask.create(taskId2, mediaAssetId, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET, Instant.now()));
            entityManager.flush();
        }).isInstanceOf(NarrationMediaCleanupTaskAlreadyExistsException.class);
    }
}
