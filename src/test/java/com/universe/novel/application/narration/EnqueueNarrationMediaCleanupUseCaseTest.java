package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.NarrationMediaCleanupTaskAlreadyExistsException;
import com.universe.novel.application.ports.NarrationMediaCleanupTaskRepositoryPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EnqueueNarrationMediaCleanupUseCase Unit Tests")
class EnqueueNarrationMediaCleanupUseCaseTest {

    @Mock
    private NarrationMediaCleanupTaskRepositoryPort repositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private EnqueueNarrationMediaCleanupUseCase useCase;

    private static final UUID TASK_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new EnqueueNarrationMediaCleanupUseCase(
                repositoryPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("1. Creates new task when none exists")
    void shouldCreateNewTaskWhenNoneExists() {
        when(repositoryPort.findByMediaAssetId(MEDIA_ASSET_ID)).thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(TASK_ID);
        when(clockPort.now()).thenReturn(NOW);

        NarrationMediaCleanupTask savedTask = NarrationMediaCleanupTask.create(
                TASK_ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW
        );
        when(repositoryPort.save(any(NarrationMediaCleanupTask.class))).thenReturn(savedTask);

        NarrationMediaCleanupTask result = useCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(TASK_ID);
        assertThat(result.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.getReason()).isEqualTo(NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        assertThat(result.getAttemptCount()).isEqualTo(0);
        assertThat(result.getCreatedAt()).isEqualTo(NOW);

        ArgumentCaptor<NarrationMediaCleanupTask> captor = ArgumentCaptor.forClass(NarrationMediaCleanupTask.class);
        verify(repositoryPort).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(TASK_ID);
        assertThat(captor.getValue().getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(captor.getValue().getReason()).isEqualTo(NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
    }

    @Test
    @DisplayName("2. Existing task -> idempotent success without creating duplicate")
    void shouldReturnExistingTaskWhenAlreadyPresent() {
        NarrationMediaCleanupTask existing = NarrationMediaCleanupTask.create(
                TASK_ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW.minusSeconds(60)
        );
        when(repositoryPort.findByMediaAssetId(MEDIA_ASSET_ID)).thenReturn(Optional.of(existing));

        NarrationMediaCleanupTask result = useCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);

        assertThat(result).isSameAs(existing);
        verify(repositoryPort, never()).save(any());
        verify(idGeneratorPort, never()).generate();
        verify(clockPort, never()).now();
    }

    @Test
    @DisplayName("3. Existing task preserves attemptCount, timestamps, diagnostics, and reason")
    void shouldPreserveExistingTaskStateOnIdempotentCall() {
        Instant createdAt = NOW.minusSeconds(300);
        Instant lastAttemptAt = NOW.minusSeconds(50);
        Instant updatedAt = NOW.minusSeconds(50);

        NarrationMediaCleanupTask existing = NarrationMediaCleanupTask.rehydrate(
                TASK_ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET,
                4,
                "StorageException",
                createdAt,
                lastAttemptAt,
                updatedAt
        );

        when(repositoryPort.findByMediaAssetId(MEDIA_ASSET_ID)).thenReturn(Optional.of(existing));

        NarrationMediaCleanupTask result = useCase.execute(new EnqueueNarrationMediaCleanupCommand(
                MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET
        ));

        assertThat(result.getId()).isEqualTo(TASK_ID);
        assertThat(result.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.getReason()).isEqualTo(NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        assertThat(result.getAttemptCount()).isEqualTo(4);
        assertThat(result.getLastErrorType()).isEqualTo("StorageException");
        assertThat(result.getCreatedAt()).isEqualTo(createdAt);
        assertThat(result.getLastAttemptAt()).isEqualTo(lastAttemptAt);
        assertThat(result.getUpdatedAt()).isEqualTo(updatedAt);

        verify(repositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("4. Concurrent duplicate save -> reload existing -> success")
    void shouldRecoverFromConcurrentDuplicateRaceByReloadingExisting() {
        when(repositoryPort.findByMediaAssetId(MEDIA_ASSET_ID))
                .thenReturn(Optional.empty()) // first check: not found
                .thenReturn(Optional.of(NarrationMediaCleanupTask.create(
                        UUID.randomUUID(), MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW
                ))); // reload after race conflict

        when(idGeneratorPort.generate()).thenReturn(TASK_ID);
        when(clockPort.now()).thenReturn(NOW);

        when(repositoryPort.save(any(NarrationMediaCleanupTask.class)))
                .thenThrow(new NarrationMediaCleanupTaskAlreadyExistsException(MEDIA_ASSET_ID));

        NarrationMediaCleanupTask result = useCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThat(result).isNotNull();
        assertThat(result.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        verify(repositoryPort).save(any());
    }

    @Test
    @DisplayName("5. Duplicate save but reload missing -> exception propagates")
    void shouldPropagateDuplicateExceptionWhenReloadFails() {
        when(repositoryPort.findByMediaAssetId(MEDIA_ASSET_ID))
                .thenReturn(Optional.empty()) // first check
                .thenReturn(Optional.empty()); // reload also empty

        when(idGeneratorPort.generate()).thenReturn(TASK_ID);
        when(clockPort.now()).thenReturn(NOW);

        when(repositoryPort.save(any(NarrationMediaCleanupTask.class)))
                .thenThrow(new NarrationMediaCleanupTaskAlreadyExistsException(MEDIA_ASSET_ID));

        assertThatThrownBy(() -> useCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .isInstanceOf(NarrationMediaCleanupTaskAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Should reject null arguments on execute")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> useCase.execute(null, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(MEDIA_ASSET_ID, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
