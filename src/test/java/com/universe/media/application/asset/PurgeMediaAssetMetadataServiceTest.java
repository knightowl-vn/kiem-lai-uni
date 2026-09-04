package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurgeMediaAssetMetadataServiceTest {

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VERSION_1_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID VERSION_2_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock
    private MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    private PurgeMediaAssetMetadataService service;

    @BeforeEach
    void setUp() {
        service = new PurgeMediaAssetMetadataService(
                mediaImageVariantRepositoryPort,
                mediaAssetVersionRepositoryPort,
                mediaAssetRepositoryPort
        );
    }

    private MediaAsset createAsset(MediaAssetStatus status) {
        return MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                status,
                2,
                NOW,
                NOW
        );
    }

    private MediaAssetVersion createVersion(UUID versionId, int versionNumber) {
        return MediaAssetVersion.rehydrate(
                versionId,
                ASSET_ID,
                versionNumber,
                StorageLocation.of(StorageProviderId.of("local"), StorageKey.of("objects/v" + versionNumber + ".png")),
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/png"),
                1024L,
                "v" + versionNumber + ".png",
                NOW
        );
    }

    @Test
    @DisplayName("execute re-reads DELETED asset, loads its version IDs, and deletes variants -> versions -> asset root")
    void shouldExecuteBottomUpDeletionInStrictOrderWhenDeleted() {
        MediaAsset deletedAsset = createAsset(MediaAssetStatus.DELETED);
        MediaAssetVersion v1 = createVersion(VERSION_1_ID, 1);
        MediaAssetVersion v2 = createVersion(VERSION_2_ID, 2);
        List<UUID> versionIds = List.of(VERSION_1_ID, VERSION_2_ID);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(deletedAsset));
        when(mediaAssetVersionRepositoryPort.findAllByAssetId(ASSET_ID)).thenReturn(List.of(v1, v2));

        service.execute(ASSET_ID);

        InOrder inOrder = inOrder(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                mediaImageVariantRepositoryPort
        );

        inOrder.verify(mediaAssetRepositoryPort).findById(ASSET_ID);
        inOrder.verify(mediaAssetVersionRepositoryPort).findAllByAssetId(ASSET_ID);
        inOrder.verify(mediaImageVariantRepositoryPort).deleteByVersionIds(versionIds);
        inOrder.verify(mediaAssetVersionRepositoryPort).deleteByAssetId(ASSET_ID);
        inOrder.verify(mediaAssetRepositoryPort).deleteById(ASSET_ID);
    }

    @Test
    @DisplayName("execute returns idempotently without error or delete calls when asset is already missing")
    void shouldReturnIdempotentlyWhenAssetAlreadyMissing() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.empty());

        service.execute(ASSET_ID);

        verify(mediaAssetVersionRepositoryPort, never()).findAllByAssetId(any());
        verify(mediaImageVariantRepositoryPort, never()).deleteByVersionIds(any());
        verify(mediaAssetVersionRepositoryPort, never()).deleteByAssetId(any());
        verify(mediaAssetRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("execute throws IllegalStateException and refuses deletion when asset status is ACTIVE")
    void shouldRefuseDeletionWhenAssetIsActive() {
        MediaAsset activeAsset = createAsset(MediaAssetStatus.ACTIVE);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(activeAsset));

        assertThatThrownBy(() -> service.execute(ASSET_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("Only DELETED assets may be purged");

        verify(mediaAssetVersionRepositoryPort, never()).findAllByAssetId(any());
        verify(mediaImageVariantRepositoryPort, never()).deleteByVersionIds(any());
        verify(mediaAssetVersionRepositoryPort, never()).deleteByAssetId(any());
        verify(mediaAssetRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("execute throws IllegalStateException and refuses deletion when asset status is ARCHIVED")
    void shouldRefuseDeletionWhenAssetIsArchived() {
        MediaAsset archivedAsset = createAsset(MediaAssetStatus.ARCHIVED);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(archivedAsset));

        assertThatThrownBy(() -> service.execute(ASSET_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED")
                .hasMessageContaining("Only DELETED assets may be purged");

        verify(mediaAssetVersionRepositoryPort, never()).findAllByAssetId(any());
        verify(mediaImageVariantRepositoryPort, never()).deleteByVersionIds(any());
        verify(mediaAssetVersionRepositoryPort, never()).deleteByAssetId(any());
        verify(mediaAssetRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("execute skips variant deletion when asset has zero versions")
    void shouldSkipVariantDeletionWhenNoVersions() {
        MediaAsset deletedAsset = createAsset(MediaAssetStatus.DELETED);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(deletedAsset));
        when(mediaAssetVersionRepositoryPort.findAllByAssetId(ASSET_ID)).thenReturn(List.of());

        service.execute(ASSET_ID);

        verify(mediaImageVariantRepositoryPort, never()).deleteByVersionIds(any());
        verify(mediaAssetVersionRepositoryPort).deleteByAssetId(ASSET_ID);
        verify(mediaAssetRepositoryPort).deleteById(ASSET_ID);
    }
}
