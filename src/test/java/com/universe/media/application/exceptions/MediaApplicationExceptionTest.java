package com.universe.media.application.exceptions;

import com.universe.media.domain.StorageLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaApplicationExceptionTest {

    @Test
    @DisplayName("MediaAssetNotFoundException sets correct error code and message")
    void shouldCreateMediaAssetNotFoundException() {
        UUID assetId = UUID.randomUUID();
        MediaAssetNotFoundException ex = new MediaAssetNotFoundException(assetId);

        assertThat(ex.getErrorCode()).isEqualTo("MEDIA_ASSET_NOT_FOUND");
        assertThat(ex.getMessage()).contains(assetId.toString());
    }

    @Test
    @DisplayName("MediaAssetVersionNotFoundException sets correct error code and message for asset + version")
    void shouldCreateMediaAssetVersionNotFoundExceptionByVersionNumber() {
        UUID assetId = UUID.randomUUID();
        MediaAssetVersionNotFoundException ex = new MediaAssetVersionNotFoundException(assetId, 2);

        assertThat(ex.getErrorCode()).isEqualTo("MEDIA_ASSET_VERSION_NOT_FOUND");
        assertThat(ex.getMessage()).contains(assetId.toString()).contains("2");
    }

    @Test
    @DisplayName("DuplicateStorageLocationException sets correct error code and message")
    void shouldCreateDuplicateStorageLocationException() {
        StorageLocation location = StorageLocation.of("s3", "backup/key.png");
        DuplicateStorageLocationException ex = new DuplicateStorageLocationException(location);

        assertThat(ex.getErrorCode()).isEqualTo("MEDIA_DUPLICATE_STORAGE_LOCATION");
        assertThat(ex.getMessage()).contains("s3").contains("backup/key.png");
    }

    @Test
    @DisplayName("DuplicateStorageLocationException rejects null StorageLocation")
    void shouldRejectNullStorageLocation() {
        assertThatThrownBy(() -> new DuplicateStorageLocationException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StorageLocation cannot be null");
    }
}
