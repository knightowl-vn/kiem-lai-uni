package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NarrationMediaCleanupTaskPersistenceAdapter Unit Tests")
class NarrationMediaCleanupTaskPersistenceAdapterTest {

    @Mock
    private SpringDataNarrationMediaCleanupTaskJpaRepository repository;

    private NarrationMediaCleanupTaskPersistenceAdapter adapter;

    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new NarrationMediaCleanupTaskPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Should find cleanup task by id")
    void shouldFindById() {
        NarrationMediaCleanupTaskJpaEntity entity = new NarrationMediaCleanupTaskJpaEntity(
                ID.toString(),
                MEDIA_ASSET_ID.toString(),
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                0,
                null,
                NOW,
                null,
                NOW
        );

        when(repository.findById(ID.toString())).thenReturn(Optional.of(entity));

        Optional<NarrationMediaCleanupTask> result = adapter.findById(ID);

        assertThat(result).isPresent();
        NarrationMediaCleanupTask task = result.get();
        assertThat(task.getId()).isEqualTo(ID);
        assertThat(task.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(task.getReason()).isEqualTo(NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        assertThat(task.getAttemptCount()).isEqualTo(0);
        assertThat(task.getLastErrorType()).isNull();
        assertThat(task.getCreatedAt()).isEqualTo(NOW);
        assertThat(task.getLastAttemptAt()).isNull();
        assertThat(task.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should return empty optional when findById receives null")
    void shouldReturnEmptyWhenFindByIdNull() {
        assertThat(adapter.findById(null)).isEmpty();
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("Should find cleanup task by mediaAssetId")
    void shouldFindByMediaAssetId() {
        NarrationMediaCleanupTaskJpaEntity entity = new NarrationMediaCleanupTaskJpaEntity(
                ID.toString(),
                MEDIA_ASSET_ID.toString(),
                NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET,
                2,
                "IOException",
                NOW.minusSeconds(100),
                NOW.minusSeconds(20),
                NOW.minusSeconds(20)
        );

        when(repository.findByMediaAssetId(MEDIA_ASSET_ID.toString())).thenReturn(Optional.of(entity));

        Optional<NarrationMediaCleanupTask> result = adapter.findByMediaAssetId(MEDIA_ASSET_ID);

        assertThat(result).isPresent();
        NarrationMediaCleanupTask task = result.get();
        assertThat(task.getId()).isEqualTo(ID);
        assertThat(task.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(task.getReason()).isEqualTo(NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        assertThat(task.getAttemptCount()).isEqualTo(2);
        assertThat(task.getLastErrorType()).isEqualTo("IOException");
        assertThat(task.getCreatedAt()).isEqualTo(NOW.minusSeconds(100));
        assertThat(task.getLastAttemptAt()).isEqualTo(NOW.minusSeconds(20));
        assertThat(task.getUpdatedAt()).isEqualTo(NOW.minusSeconds(20));
    }

    @Test
    @DisplayName("Should return empty optional when findByMediaAssetId receives null")
    void shouldReturnEmptyWhenFindByMediaAssetIdNull() {
        assertThat(adapter.findByMediaAssetId(null)).isEmpty();
        verify(repository, never()).findByMediaAssetId(any());
    }

    @Test
    @DisplayName("Should find oldest cleanup tasks bounded by limit")
    void shouldFindOldestWithLimit() {
        NarrationMediaCleanupTaskJpaEntity entity1 = new NarrationMediaCleanupTaskJpaEntity(
                ID.toString(),
                MEDIA_ASSET_ID.toString(),
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                0,
                null,
                NOW,
                null,
                NOW
        );

        when(repository.findOldest(PageRequest.of(0, 5))).thenReturn(List.of(entity1));

        List<NarrationMediaCleanupTask> results = adapter.findOldest(5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("Should return empty list when findOldest receives non-positive limit")
    void shouldReturnEmptyWhenFindOldestLimitNonPositive() {
        assertThat(adapter.findOldest(0)).isEmpty();
        assertThat(adapter.findOldest(-1)).isEmpty();
        verify(repository, never()).findOldest(any());
    }

    @Test
    @DisplayName("Should save cleanup task and map correctly to JPA entity")
    void shouldSaveCleanupTask() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.rehydrate(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET,
                1,
                "ConnectException",
                NOW.minusSeconds(60),
                NOW,
                NOW
        );

        NarrationMediaCleanupTaskJpaEntity entity = new NarrationMediaCleanupTaskJpaEntity(
                ID.toString(),
                MEDIA_ASSET_ID.toString(),
                NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET,
                1,
                "ConnectException",
                NOW.minusSeconds(60),
                NOW,
                NOW
        );

        when(repository.saveAndFlush(any(NarrationMediaCleanupTaskJpaEntity.class))).thenReturn(entity);

        NarrationMediaCleanupTask saved = adapter.save(task);

        ArgumentCaptor<NarrationMediaCleanupTaskJpaEntity> captor =
                ArgumentCaptor.forClass(NarrationMediaCleanupTaskJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        NarrationMediaCleanupTaskJpaEntity captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo(ID.toString());
        assertThat(captured.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID.toString());
        assertThat(captured.getReason()).isEqualTo(NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        assertThat(captured.getAttemptCount()).isEqualTo(1);
        assertThat(captured.getLastErrorType()).isEqualTo("ConnectException");
        assertThat(captured.getCreatedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(captured.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(captured.getUpdatedAt()).isEqualTo(NOW);

        assertThat(saved.getId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("Should reject saving null cleanup task")
    void shouldRejectSaveNull() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should translate uq_novel_narration_media_cleanup_tasks_media_asset to NarrationMediaCleanupTaskAlreadyExistsException")
    void shouldTranslateUniqueConstraintViolation() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW
        );

        org.hibernate.exception.ConstraintViolationException cve = new org.hibernate.exception.ConstraintViolationException(
                "Duplicate entry", null, "uq_novel_narration_media_cleanup_tasks_media_asset"
        );
        org.springframework.dao.DataIntegrityViolationException dive =
                new org.springframework.dao.DataIntegrityViolationException("Constraint violation", cve);

        when(repository.saveAndFlush(any(NarrationMediaCleanupTaskJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(task))
                .isInstanceOf(com.universe.novel.application.exceptions.NarrationMediaCleanupTaskAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Should rethrow unrelated DataIntegrityViolationException without translating")
    void shouldRethrowUnrelatedDataIntegrityViolation() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW
        );

        org.springframework.dao.DataIntegrityViolationException dive =
                new org.springframework.dao.DataIntegrityViolationException("Unrelated constraint failure");

        when(repository.saveAndFlush(any(NarrationMediaCleanupTaskJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(task))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should delete cleanup task by id")
    void shouldDeleteById() {
        adapter.deleteById(ID);
        verify(repository).deleteById(ID.toString());
    }

    @Test
    @DisplayName("Should ignore deleteById when id is null")
    void shouldIgnoreDeleteByIdNull() {
        adapter.deleteById(null);
        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("Should delete cleanup task by mediaAssetId")
    void shouldDeleteByMediaAssetId() {
        adapter.deleteByMediaAssetId(MEDIA_ASSET_ID);
        verify(repository).deleteByMediaAssetId(MEDIA_ASSET_ID.toString());
    }

    @Test
    @DisplayName("Should ignore deleteByMediaAssetId when mediaAssetId is null")
    void shouldIgnoreDeleteByMediaAssetIdNull() {
        adapter.deleteByMediaAssetId(null);
        verify(repository, never()).deleteByMediaAssetId(any());
    }
}
