package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.contracts.dto.MediaAssetStreamDTO;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ContentHashStatus;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
@DisplayName("StreamMediaAssetUseCase Unit Tests")
class StreamMediaAssetUseCaseTest {

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private StorageProviderResolverPort storageProviderResolverPort;

    @Mock
    private BinaryStoragePort binaryStoragePort;

    private StreamMediaAssetUseCase useCase;

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final StorageProviderId PROVIDER_ID = StorageProviderId.of("b2");
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/test.mp4");
    private static final StorageLocation LOCATION = StorageLocation.of(PROVIDER_ID, STORAGE_KEY);
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new StreamMediaAssetUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                storageProviderResolverPort
        );
    }

    private MediaAsset createAsset(MediaAssetStatus status, MediaVisibility visibility) {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.VIDEO,
                visibility,
                "screen-video",
                NOW
        );
        if (status == MediaAssetStatus.ARCHIVED) {
            asset.archive(NOW.plusSeconds(1));
        } else if (status == MediaAssetStatus.DELETED) {
            asset.markDeleted(NOW.plusSeconds(1));
        }
        return asset;
    }

    private MediaAssetVersion createVersion(long sizeBytes) {
        return MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                1,
                LOCATION,
                null,
                ContentHash.of("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                MimeType.of("video/mp4"),
                sizeBytes,
                "video.mp4",
                NOW
        );
    }

    @Test
    @DisplayName("openStream: successfully returns stream for ACTIVE and PRIVATE asset")
    void openStream_activePrivateAsset_success() throws Exception {
        MediaAsset asset = createAsset(MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE);
        MediaAssetVersion version = createVersion(5000000L);
        InputStream mockContent = new ByteArrayInputStream("video-data".getBytes());

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(storageProviderResolverPort.resolve(PROVIDER_ID)).thenReturn(binaryStoragePort);
        when(binaryStoragePort.open(STORAGE_KEY)).thenReturn(mockContent);

        try (MediaAssetStreamDTO result = useCase.openStream(ASSET_ID)) {
            assertThat(result.assetId()).isEqualTo(ASSET_ID);
            assertThat(result.versionNumber()).isEqualTo(1);
            assertThat(result.totalSizeBytes()).isEqualTo(5000000L);
            assertThat(result.mimeType()).isEqualTo("video/mp4");
            assertThat(result.contentStream()).isNotNull();
        }

        verify(binaryStoragePort).open(STORAGE_KEY);
    }

    @Test
    @DisplayName("openRangeStream: successfully returns range stream for ACTIVE and PRIVATE asset")
    void openRangeStream_activePrivateAsset_success() throws Exception {
        MediaAsset asset = createAsset(MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE);
        MediaAssetVersion version = createVersion(5000000L);
        InputStream mockContent = new ByteArrayInputStream("partial-video-data".getBytes());

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(storageProviderResolverPort.resolve(PROVIDER_ID)).thenReturn(binaryStoragePort);
        when(binaryStoragePort.openRange(STORAGE_KEY, 0L, 1024L)).thenReturn(mockContent);

        try (MediaAssetStreamDTO result = useCase.openRangeStream(ASSET_ID, 0L, 1024L)) {
            assertThat(result.assetId()).isEqualTo(ASSET_ID);
            assertThat(result.versionNumber()).isEqualTo(1);
            assertThat(result.totalSizeBytes()).isEqualTo(5000000L);
            assertThat(result.contentStream()).isNotNull();
        }

        verify(binaryStoragePort).openRange(STORAGE_KEY, 0L, 1024L);
    }

    @Test
    @DisplayName("openStream: rejects when asset not found")
    void openStream_assetNotFound_throws() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.openStream(ASSET_ID))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("openStream: rejects when asset is DELETED")
    void openStream_assetDeleted_throws() {
        MediaAsset asset = createAsset(MediaAssetStatus.DELETED, MediaVisibility.PRIVATE);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.openStream(ASSET_ID))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("openRangeStream: rejects negative start offset and never calls storage port")
    void openRangeStream_negativeStart_throws() {
        assertThatThrownBy(() -> useCase.openRangeStream(ASSET_ID, -1L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startInclusive cannot be negative");

        verify(binaryStoragePort, never()).openRange(any(), any(long.class), any(long.class));
    }

    @Test
    @DisplayName("openRangeStream: rejects zero length and never calls storage port")
    void openRangeStream_zeroLength_throws() {
        assertThatThrownBy(() -> useCase.openRangeStream(ASSET_ID, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length must be positive");

        verify(binaryStoragePort, never()).openRange(any(), any(long.class), any(long.class));
    }

    @Test
    @DisplayName("openRangeStream: rejects negative length and never calls storage port")
    void openRangeStream_negativeLength_throws() {
        assertThatThrownBy(() -> useCase.openRangeStream(ASSET_ID, 0L, -10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length must be positive");

        verify(binaryStoragePort, never()).openRange(any(), any(long.class), any(long.class));
    }

    @Test
    @DisplayName("openRangeStream: rejects range exceeding total version size and never calls storage port")
    void openRangeStream_exceedsTotalSize_throws() {
        MediaAsset asset = createAsset(MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE);
        MediaAssetVersion version = createVersion(1000L);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> useCase.openRangeStream(ASSET_ID, 500L, 600L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds version size 1000");

        verify(binaryStoragePort, never()).openRange(any(), any(long.class), any(long.class));
    }

    @Test
    @DisplayName("openRangeStream: rejects when startInclusive equals total version size and never calls storage port")
    void openRangeStream_startEqualsTotalSize_throws() {
        MediaAsset asset = createAsset(MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE);
        MediaAssetVersion version = createVersion(1000L);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> useCase.openRangeStream(ASSET_ID, 1000L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds version size 1000");

        verify(binaryStoragePort, never()).openRange(any(), any(long.class), any(long.class));
    }

    @Test
    @DisplayName("openRangeStream: rejects when startInclusive is Long.MAX_VALUE without overflow")
    void openRangeStream_startLongMaxValue_throws() {
        MediaAsset asset = createAsset(MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE);
        MediaAssetVersion version = createVersion(1000L);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> useCase.openRangeStream(ASSET_ID, Long.MAX_VALUE, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds version size 1000");

        verify(binaryStoragePort, never()).openRange(any(), any(long.class), any(long.class));
    }

    @Test
    @DisplayName("openRangeStream: rejects when length is Long.MAX_VALUE without overflow")
    void openRangeStream_lengthLongMaxValue_throws() {
        MediaAsset asset = createAsset(MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE);
        MediaAssetVersion version = createVersion(1000L);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> useCase.openRangeStream(ASSET_ID, 0L, Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds version size 1000");

        verify(binaryStoragePort, never()).openRange(any(), any(long.class), any(long.class));
    }
}
