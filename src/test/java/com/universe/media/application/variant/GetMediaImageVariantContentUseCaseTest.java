package com.universe.media.application.variant;

import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.MediaImageVariantNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetMediaImageVariantContentUseCaseTest {

    private MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;
    private BinaryStoragePort binaryStoragePort;

    private GetMediaImageVariantContentUseCase useCase;

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID V1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID V2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final StorageProviderId PROVIDER_ID = StorageProviderId.of("local");
    private static final StorageKey VARIANT_KEY_STORAGE = StorageKey.of("objects/variants/derivative_w300.jpg");
    private static final StorageLocation VARIANT_LOCATION = StorageLocation.of(PROVIDER_ID, VARIANT_KEY_STORAGE);
    private static final StorageKey SOURCE_KEY = StorageKey.of("objects/source.jpg");
    private static final StorageLocation SOURCE_LOCATION = StorageLocation.of(PROVIDER_ID, SOURCE_KEY);
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        mediaAssetRepositoryPort = mock(MediaAssetRepositoryPort.class);
        mediaAssetVersionRepositoryPort = mock(MediaAssetVersionRepositoryPort.class);
        mediaImageVariantRepositoryPort = mock(MediaImageVariantRepositoryPort.class);
        binaryStoragePort = mock(BinaryStoragePort.class);

        when(binaryStoragePort.providerId()).thenReturn(PROVIDER_ID);

        useCase = new GetMediaImageVariantContentUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                mediaImageVariantRepositoryPort,
                binaryStoragePort
        );
    }

    private MediaAsset createAsset(MediaType type, MediaAssetStatus status, MediaVisibility visibility, int currentVersion) {
        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, type, visibility, NOW);
        if (currentVersion > 1) {
            for (int i = 2; i <= currentVersion; i++) {
                asset.registerNextVersion(NOW);
            }
        }
        if (status == MediaAssetStatus.ARCHIVED) {
            asset.archive(NOW);
        } else if (status == MediaAssetStatus.DELETED) {
            asset.markDeleted(NOW);
        }
        return asset;
    }

    private MediaAssetVersion createVersion(UUID versionId, int versionNumber) {
        return MediaAssetVersion.create(
                versionId,
                ASSET_ID,
                versionNumber,
                SOURCE_LOCATION,
                null,
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("image/jpeg"),
                100000L,
                "photo.jpg",
                NOW
        );
    }

    private MediaImageVariant createVariant(UUID versionId, String variantKey) {
        return MediaImageVariant.create(
                UUID.randomUUID(),
                versionId,
                ImageVariantSpec.of(300),
                VARIANT_LOCATION,
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                25000L,
                300,
                200,
                NOW
        );
    }

    @Test
    @DisplayName("delivers variant binary for current version of ACTIVE and PUBLIC IMAGE asset")
    void shouldDeliverCurrentVersionVariantSuccessfully() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(V1_ID, 1);
        MediaImageVariant variant = createVariant(V1_ID, "w300");

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(V1_ID, "w300")).thenReturn(Optional.of(variant));

        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        when(binaryStoragePort.open(VARIANT_KEY_STORAGE)).thenReturn(stream);

        GetMediaAssetContentResult result = useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300"));

        assertThat(result).isNotNull();
        assertThat(result.content()).isSameAs(stream);
        assertThat(result.sizeBytes()).isEqualTo(25000L);
        assertThat(result.mimeType()).isEqualTo("image/jpeg");
        assertThat(result.contentHash()).isEqualTo("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2");

        // Open must use the variant storage key, never source key
        verify(binaryStoragePort).open(VARIANT_KEY_STORAGE);
        verify(binaryStoragePort, never()).open(SOURCE_KEY);
    }

    @Test
    @DisplayName("uses current version (v2) and does not serve variant associated only with old version (v1)")
    void shouldNotServeOldVersionVariantWhenAssetIsAtNewerVersion() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 2);
        MediaAssetVersion currentVersion = createVersion(V2_ID, 2);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 2)).thenReturn(Optional.of(currentVersion));
        // Variant exists for v1, but does NOT exist for current version v2
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(V2_ID, "w300")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(MediaImageVariantNotFoundException.class)
                .hasMessageContaining("Media image variant not found for asset: " + ASSET_ID + ", version: 2, key: w300");

        // Verify storage open was never called and no fallback to source content occurred
        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("missing variant throws MediaImageVariantNotFoundException without source fallback")
    void shouldThrowWhenVariantDoesNotExistWithoutSourceFallback() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(V1_ID, 1);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(V1_ID, "w500")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w500")))
                .isInstanceOf(MediaImageVariantNotFoundException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("unavailable when asset is ARCHIVED")
    void shouldThrowNotFoundWhenAssetIsArchived() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ARCHIVED, MediaVisibility.PUBLIC, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("unavailable when asset is DELETED")
    void shouldThrowNotFoundWhenAssetIsDeleted() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.DELETED, MediaVisibility.PUBLIC, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("unavailable when asset is PRIVATE")
    void shouldThrowNotFoundWhenAssetIsPrivate() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("unavailable when asset is RESTRICTED")
    void shouldThrowNotFoundWhenAssetIsRestricted() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.RESTRICTED, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("unavailable when asset is not of type IMAGE")
    void shouldThrowNotFoundWhenAssetIsNotImage() {
        MediaAsset asset = createAsset(MediaType.VIDEO, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("fails safely when storage provider mismatches")
    void shouldThrowStorageExceptionWhenProviderMismatch() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(V1_ID, 1);
        MediaImageVariant variant = MediaImageVariant.create(
                UUID.randomUUID(),
                V1_ID,
                ImageVariantSpec.of(300),
                StorageLocation.of("s3", "objects/variants/s3_variant.jpg"),
                ContentHash.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                MimeType.of("image/jpeg"),
                25000L,
                300,
                200,
                NOW
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(V1_ID, "w300")).thenReturn(Optional.of(variant));

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Storage provider mismatch for variant w300");

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("throws when asset does not exist")
    void shouldThrowWhenAssetDoesNotExist() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("exact lookup: W300, leading-space, and trailing-space keys query repository exactly and do not resolve")
    void shouldNotNormalizeVariantKeysAndFailWhenExactMatchNotFound() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(V1_ID, 1);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        // Repository returns empty for non-exact keys
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(V1_ID, "W300")).thenReturn(Optional.empty());
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(V1_ID, " w300")).thenReturn(Optional.empty());
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(V1_ID, "w300 ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "W300")))
                .isInstanceOf(MediaImageVariantNotFoundException.class);

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, " w300")))
                .isInstanceOf(MediaImageVariantNotFoundException.class);

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300 ")))
                .isInstanceOf(MediaImageVariantNotFoundException.class);

        verify(mediaImageVariantRepositoryPort).findByVersionIdAndVariantKey(V1_ID, "W300");
        verify(mediaImageVariantRepositoryPort).findByVersionIdAndVariantKey(V1_ID, " w300");
        verify(mediaImageVariantRepositoryPort).findByVersionIdAndVariantKey(V1_ID, "w300 ");
        verify(mediaImageVariantRepositoryPort, never()).findByVersionIdAndVariantKey(V1_ID, "w300");
    }

    @Test
    @DisplayName("throws when current version does not exist")
    void shouldThrowWhenCurrentVersionDoesNotExist() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);
    }
}
