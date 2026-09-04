package com.universe.media.application.asset;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurgeExpiredDeletedMediaAssetsUseCaseTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final ClockPort FIXED_CLOCK = () -> FIXED_NOW;

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private PurgeDeletedMediaAssetUseCase purgeDeletedMediaAssetUseCase;

    private PurgeExpiredDeletedMediaAssetsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PurgeExpiredDeletedMediaAssetsUseCase(
                mediaAssetRepositoryPort,
                purgeDeletedMediaAssetUseCase,
                FIXED_CLOCK
        );
    }

    private MediaAsset createDeletedAsset(UUID id, Instant deletedAt) {
        return MediaAsset.rehydrate(
                id,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.DELETED,
                1,
                deletedAt.minus(Duration.ofDays(5)),
                deletedAt
        );
    }

    @Test
    @DisplayName("purges all candidates in batch successfully and returns accurate counts")
    void shouldPurgeAllExpiredDeletedCandidatesInBatch() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        Instant deletedAt = FIXED_NOW.minus(Duration.ofDays(10));

        List<MediaAsset> candidates = List.of(
                createDeletedAsset(id1, deletedAt),
                createDeletedAsset(id2, deletedAt),
                createDeletedAsset(id3, deletedAt)
        );

        Instant expectedCutoff = FIXED_NOW.minus(Duration.ofDays(7));
        when(mediaAssetRepositoryPort.findExpiredDeleted(expectedCutoff, 50)).thenReturn(candidates);

        when(purgeDeletedMediaAssetUseCase.execute(new PurgeDeletedMediaAssetCommand(id1)))
                .thenReturn(new PurgeDeletedMediaAssetResult(id1, 1, 1, FIXED_NOW));
        when(purgeDeletedMediaAssetUseCase.execute(new PurgeDeletedMediaAssetCommand(id2)))
                .thenReturn(new PurgeDeletedMediaAssetResult(id2, 1, 0, FIXED_NOW));
        when(purgeDeletedMediaAssetUseCase.execute(new PurgeDeletedMediaAssetCommand(id3)))
                .thenReturn(new PurgeDeletedMediaAssetResult(id3, 2, 2, FIXED_NOW));

        PurgeExpiredDeletedMediaAssetsResult result = useCase.execute(new PurgeExpiredDeletedMediaAssetsCommand(50));

        assertThat(result.candidates()).isEqualTo(3);
        assertThat(result.purged()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.executedAt()).isEqualTo(FIXED_NOW);

        verify(purgeDeletedMediaAssetUseCase).execute(new PurgeDeletedMediaAssetCommand(id1));
        verify(purgeDeletedMediaAssetUseCase).execute(new PurgeDeletedMediaAssetCommand(id2));
        verify(purgeDeletedMediaAssetUseCase).execute(new PurgeDeletedMediaAssetCommand(id3));
    }

    @Test
    @DisplayName("isolates failure per candidate so subsequent items in the batch continue processing")
    void shouldIsolateFailurePerItemAndContinueProcessingRemainingCandidates() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        Instant deletedAt = FIXED_NOW.minus(Duration.ofDays(10));

        List<MediaAsset> candidates = List.of(
                createDeletedAsset(id1, deletedAt),
                createDeletedAsset(id2, deletedAt),
                createDeletedAsset(id3, deletedAt)
        );

        Instant expectedCutoff = FIXED_NOW.minus(Duration.ofDays(7));
        when(mediaAssetRepositoryPort.findExpiredDeleted(expectedCutoff, 50)).thenReturn(candidates);

        when(purgeDeletedMediaAssetUseCase.execute(new PurgeDeletedMediaAssetCommand(id1)))
                .thenReturn(new PurgeDeletedMediaAssetResult(id1, 1, 1, FIXED_NOW));
        doThrow(new StorageException("Storage device unreachable"))
                .when(purgeDeletedMediaAssetUseCase).execute(new PurgeDeletedMediaAssetCommand(id2));
        when(purgeDeletedMediaAssetUseCase.execute(new PurgeDeletedMediaAssetCommand(id3)))
                .thenReturn(new PurgeDeletedMediaAssetResult(id3, 2, 2, FIXED_NOW));

        PurgeExpiredDeletedMediaAssetsResult result = useCase.execute(new PurgeExpiredDeletedMediaAssetsCommand(50));

        assertThat(result.candidates()).isEqualTo(3);
        assertThat(result.purged()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.executedAt()).isEqualTo(FIXED_NOW);

        verify(purgeDeletedMediaAssetUseCase).execute(new PurgeDeletedMediaAssetCommand(id1));
        verify(purgeDeletedMediaAssetUseCase).execute(new PurgeDeletedMediaAssetCommand(id2));
        verify(purgeDeletedMediaAssetUseCase).execute(new PurgeDeletedMediaAssetCommand(id3));
    }

    @Test
    @DisplayName("returns zero counts when no expired candidates are found")
    void shouldReturnZeroCountsWhenNoExpiredCandidatesFound() {
        Instant expectedCutoff = FIXED_NOW.minus(Duration.ofDays(7));
        when(mediaAssetRepositoryPort.findExpiredDeleted(expectedCutoff, 50)).thenReturn(List.of());

        PurgeExpiredDeletedMediaAssetsResult result = useCase.execute(new PurgeExpiredDeletedMediaAssetsCommand(50));

        assertThat(result.candidates()).isEqualTo(0);
        assertThat(result.purged()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.executedAt()).isEqualTo(FIXED_NOW);

        verify(purgeDeletedMediaAssetUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("rejects zero or negative batch size with IllegalArgumentException")
    void shouldRejectZeroOrNegativeBatchSize() {
        assertThatThrownBy(() -> useCase.execute(new PurgeExpiredDeletedMediaAssetsCommand(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");

        assertThatThrownBy(() -> useCase.execute(new PurgeExpiredDeletedMediaAssetsCommand(-10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");

        verify(mediaAssetRepositoryPort, never()).findExpiredDeleted(any(), any(Integer.class));
    }

    @Test
    @DisplayName("rejects null command with NullPointerException")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("PurgeExpiredDeletedMediaAssetsCommand cannot be null");
    }

    @Test
    @DisplayName("uses specified positive batch size when provided in command")
    void shouldUseSpecifiedPositiveBatchSize() {
        Instant expectedCutoff = FIXED_NOW.minus(Duration.ofDays(7));
        when(mediaAssetRepositoryPort.findExpiredDeleted(expectedCutoff, 25)).thenReturn(List.of());

        useCase.execute(new PurgeExpiredDeletedMediaAssetsCommand(25));

        verify(mediaAssetRepositoryPort).findExpiredDeleted(expectedCutoff, 25);
    }
}
