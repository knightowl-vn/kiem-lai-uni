package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.exceptions.MediaAssetReferencedException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenMediaAssetReferenceCheckAdapterTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTRY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ScreenEntryRepositoryPort entryRepository;

    private ScreenMediaAssetReferenceCheckAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScreenMediaAssetReferenceCheckAdapter(entryRepository);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("null asset ID is treated as a safe no-op")
    void whenAssetIdIsNull_thenDoesNothing() {
        assertThatCode(() -> adapter.ensureNotReferenced(null))
                .doesNotThrowAnyException();

        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetIdForUpdate(null);
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetId(null);
    }

    @Test
    @DisplayName("in active transaction: unreferenced asset passes without exception using locked read")
    void whenInTransaction_andAssetNotReferenced_thenPassesSuccessfully() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(ASSET_ID))
                .thenReturn(List.of());

        assertThatCode(() -> adapter.ensureNotReferenced(ASSET_ID))
                .doesNotThrowAnyException();

        verify(entryRepository).findEntryIdsByVideoMediaAssetIdForUpdate(ASSET_ID);
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetId(ASSET_ID);
    }

    @Test
    @DisplayName("in active transaction: referenced asset throws MediaAssetReferencedException with locked read")
    void whenInTransaction_andAssetIsReferenced_thenThrowsMediaAssetReferencedException() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(ASSET_ID))
                .thenReturn(List.of(ENTRY_ID));

        assertThatThrownBy(() -> adapter.ensureNotReferenced(ASSET_ID))
                .isInstanceOf(MediaAssetReferencedException.class)
                .hasMessageContaining(ASSET_ID.toString())
                .hasMessageContaining(ENTRY_ID.toString())
                .satisfies(ex -> {
                    MediaAssetReferencedException refEx = (MediaAssetReferencedException) ex;
                    assertThat(refEx.getErrorCode()).isEqualTo("MEDIA_ASSET_REFERENCED");
                    assertThat(refEx.getMediaAssetId()).isEqualTo(ASSET_ID);
                });

        verify(entryRepository).findEntryIdsByVideoMediaAssetIdForUpdate(ASSET_ID);
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetId(ASSET_ID);
    }

    @Test
    @DisplayName("outside transaction: unreferenced asset passes without exception using non-locking read")
    void whenOutsideTransaction_andAssetNotReferenced_thenPassesSuccessfully() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        when(entryRepository.findEntryIdsByVideoMediaAssetId(ASSET_ID))
                .thenReturn(List.of());

        assertThatCode(() -> adapter.ensureNotReferenced(ASSET_ID))
                .doesNotThrowAnyException();

        verify(entryRepository).findEntryIdsByVideoMediaAssetId(ASSET_ID);
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetIdForUpdate(ASSET_ID);
    }

    @Test
    @DisplayName("outside transaction: referenced asset throws MediaAssetReferencedException using non-locking read")
    void whenOutsideTransaction_andAssetIsReferenced_thenThrowsMediaAssetReferencedException() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        when(entryRepository.findEntryIdsByVideoMediaAssetId(ASSET_ID))
                .thenReturn(List.of(ENTRY_ID));

        assertThatThrownBy(() -> adapter.ensureNotReferenced(ASSET_ID))
                .isInstanceOf(MediaAssetReferencedException.class)
                .hasMessageContaining(ASSET_ID.toString())
                .hasMessageContaining(ENTRY_ID.toString())
                .satisfies(ex -> {
                    MediaAssetReferencedException refEx = (MediaAssetReferencedException) ex;
                    assertThat(refEx.getErrorCode()).isEqualTo("MEDIA_ASSET_REFERENCED");
                    assertThat(refEx.getMediaAssetId()).isEqualTo(ASSET_ID);
                });

        verify(entryRepository).findEntryIdsByVideoMediaAssetId(ASSET_ID);
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetIdForUpdate(ASSET_ID);
    }

    @Test
    @DisplayName("supports declared media type VIDEO and rejects non-VIDEO types")
    void testSupportsMediaType() {
        assertThat(adapter.supports(MediaTypeDTO.VIDEO)).isTrue();
        assertThat(adapter.supports(MediaTypeDTO.IMAGE)).isFalse();
        assertThat(adapter.supports(MediaTypeDTO.AUDIO)).isFalse();
        assertThat(adapter.supports(MediaTypeDTO.DOCUMENT)).isFalse();
        assertThat(adapter.supports(null)).isFalse();
    }
}
