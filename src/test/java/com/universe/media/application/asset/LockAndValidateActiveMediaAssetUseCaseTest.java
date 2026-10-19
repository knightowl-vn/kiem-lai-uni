package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LockAndValidateActiveMediaAssetUseCase Unit Tests")
class LockAndValidateActiveMediaAssetUseCaseTest {

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    private LockAndValidateActiveMediaAssetUseCase useCase;

    private final UUID assetId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-18T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new LockAndValidateActiveMediaAssetUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );
    }

    @Test
    @DisplayName("Lock & Validate: successfully locks active video asset and returns detail result")
    void shouldSuccessfullyLockAndValidateActiveVideoAsset() {
        MediaAsset asset = createAsset(assetId, MediaType.VIDEO, MediaAssetStatus.ACTIVE, 1);
        MediaAssetVersion version = createVersion(versionId, assetId, 1);

        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(version));

        MediaAssetDetailResult result = useCase.execute(assetId, MediaType.VIDEO);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(assetId);
        assertThat(result.mediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(result.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(result.currentVersionNumber()).isEqualTo(1);
        assertThat(result.currentVersion()).isNotNull();
        assertThat(result.currentVersion().id()).isEqualTo(versionId);

        verify(mediaAssetRepositoryPort).findByIdForUpdate(assetId);
        verify(mediaAssetVersionRepositoryPort).findByAssetIdAndVersionNumber(assetId, 1);
    }

    @Test
    @DisplayName("Lock & Validate: null expected type accepts any active media type")
    void shouldAcceptAnyMediaTypeWhenExpectedTypeIsNull() {
        MediaAsset asset = createAsset(assetId, MediaType.IMAGE, MediaAssetStatus.ACTIVE, 1);
        MediaAssetVersion version = createVersion(versionId, assetId, 1);

        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.of(version));

        MediaAssetDetailResult result = useCase.execute(assetId, null);

        assertThat(result).isNotNull();
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE);
    }

    @Test
    @DisplayName("Lock & Validate: fails when asset does not exist in repository")
    void shouldFailWhenAssetNotFound() {
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(assetId, MediaType.VIDEO))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(mediaAssetRepositoryPort).findByIdForUpdate(assetId);
    }

    @Test
    @DisplayName("Lock & Validate: fails when asset is DELETED")
    void shouldFailWhenAssetIsDeleted() {
        MediaAsset asset = createAsset(assetId, MediaType.VIDEO, MediaAssetStatus.DELETED, 1);
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(assetId, MediaType.VIDEO))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("Lock & Validate: fails when asset is ARCHIVED")
    void shouldFailWhenAssetIsArchived() {
        MediaAsset asset = createAsset(assetId, MediaType.VIDEO, MediaAssetStatus.ARCHIVED, 1);
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(assetId, MediaType.VIDEO))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    @DisplayName("Lock & Validate: fails when asset media type mismatches expected type")
    void shouldFailWhenMediaTypeMismatches() {
        MediaAsset asset = createAsset(assetId, MediaType.IMAGE, MediaAssetStatus.ACTIVE, 1);
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(assetId, MediaType.VIDEO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IMAGE")
                .hasMessageContaining("VIDEO");
    }

    @Test
    @DisplayName("Lock & Validate: fails when current version does not exist")
    void shouldFailWhenCurrentVersionNotFound() {
        MediaAsset asset = createAsset(assetId, MediaType.VIDEO, MediaAssetStatus.ACTIVE, 1);
        when(mediaAssetRepositoryPort.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(assetId, 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(assetId, MediaType.VIDEO))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);
    }

    @Test
    @DisplayName("Lock & Validate: fails when asset ID is null")
    void shouldFailWhenAssetIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, MediaType.VIDEO))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Asset ID cannot be null");
    }

    private MediaAsset createAsset(UUID id, MediaType type, MediaAssetStatus status, int versionNumber) {
        return MediaAsset.rehydrate(
                id,
                type,
                MediaVisibility.PRIVATE,
                status,
                versionNumber,
                "smoke-tag",
                now,
                now
        );
    }

    private MediaAssetVersion createVersion(UUID id, UUID aId, int versionNumber) {
        return MediaAssetVersion.create(
                id,
                aId,
                versionNumber,
                StorageLocation.of(StorageProviderId.of("b2"), StorageKey.of("videos/test.mp4")),
                "https://cdn.example.com/videos/test.mp4",
                ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                MimeType.of("video/mp4"),
                10485760L,
                "test.mp4",
                now
        );
    }
}
