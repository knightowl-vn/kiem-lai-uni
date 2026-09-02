package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
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
class GetMediaAssetContentUseCaseTest {

    private static final UUID ASSET_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID VERSION_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final Instant T1 =
            Instant.parse("2026-09-01T10:00:00Z");

    private static final String HASH =
            "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private BinaryStoragePort binaryStoragePort;

    private GetMediaAssetContentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetMediaAssetContentUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                binaryStoragePort
        );
    }

    @Test
    @DisplayName("ACTIVE + PUBLIC asset successfully returns stream and metadata")
    void shouldReturnContentStreamForActivePublicAsset() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.ACTIVE,
                1,
                T1,
                T1
        );

        MediaAssetVersion version = MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                1,
                StorageLocation.of("local", "objects/test-key"),
                null,
                ContentHash.of(HASH),
                MimeType.of("image/webp"),
                1024L,
                "cover.webp",
                T1
        );

        ByteArrayInputStream fakeStream = new ByteArrayInputStream(new byte[]{1, 2, 3, 4});

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(binaryStoragePort.providerId()).thenReturn(StorageProviderId.of("local"));
        when(binaryStoragePort.open(StorageKey.of("objects/test-key"))).thenReturn(fakeStream);

        GetMediaAssetContentResult result = useCase.execute(new GetMediaAssetContentQuery(ASSET_ID));

        assertThat(result).isNotNull();
        assertThat(result.content()).isSameAs(fakeStream);
        assertThat(result.sizeBytes()).isEqualTo(1024L);
        assertThat(result.mimeType()).isEqualTo("image/webp");
        assertThat(result.contentHash()).isEqualTo(HASH);

        verify(binaryStoragePort).open(StorageKey.of("objects/test-key"));
    }

    @Test
    @DisplayName("missing asset throws MediaAssetNotFoundException")
    void shouldThrowWhenAssetMissing() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("ARCHIVED asset throws MediaAssetNotFoundException")
    void shouldThrowWhenAssetArchived() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.ARCHIVED,
                1,
                T1,
                T1
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("DELETED asset throws MediaAssetNotFoundException")
    void shouldThrowWhenAssetDeleted() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.DELETED,
                1,
                T1,
                T1
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("PRIVATE asset throws MediaAssetNotFoundException")
    void shouldThrowWhenAssetPrivate() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PRIVATE,
                MediaAssetStatus.ACTIVE,
                1,
                T1,
                T1
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("RESTRICTED asset throws MediaAssetNotFoundException")
    void shouldThrowWhenAssetRestricted() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.RESTRICTED,
                MediaAssetStatus.ACTIVE,
                1,
                T1,
                T1
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("missing current version throws MediaAssetVersionNotFoundException")
    void shouldThrowWhenCurrentVersionMissing() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.ACTIVE,
                1,
                T1,
                T1
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("storage provider mismatch throws internal StorageException")
    void shouldThrowWhenStorageProviderMismatch() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.ACTIVE,
                1,
                T1,
                T1
        );

        MediaAssetVersion version = MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                1,
                StorageLocation.of("s3", "objects/s3-key"),
                null,
                ContentHash.of(HASH),
                MimeType.of("image/webp"),
                1024L,
                "cover.webp",
                T1
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(binaryStoragePort.providerId()).thenReturn(StorageProviderId.of("local"));

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Storage provider mismatch");

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("storage object not found propagates StorageObjectNotFoundException")
    void shouldPropagateWhenStorageObjectNotFound() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.ACTIVE,
                1,
                T1,
                T1
        );

        MediaAssetVersion version = MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                1,
                StorageLocation.of("local", "objects/missing-key"),
                null,
                ContentHash.of(HASH),
                MimeType.of("image/webp"),
                1024L,
                "cover.webp",
                T1
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(binaryStoragePort.providerId()).thenReturn(StorageProviderId.of("local"));
        when(binaryStoragePort.open(StorageKey.of("objects/missing-key")))
                .thenThrow(new StorageObjectNotFoundException(StorageKey.of("objects/missing-key")));

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }
}
