package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionContentHashMismatchException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
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
class OpenMediaAssetVersionContentUseCaseTest {

    private static final UUID ASSET_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID VERSION_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final Instant T1 =
            Instant.parse("2026-09-01T10:00:00Z");

    private static final String HASH =
            "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";

    private static final String OTHER_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private BinaryStoragePort binaryStoragePort;

    private OpenMediaAssetVersionContentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new OpenMediaAssetVersionContentUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                binaryStoragePort
        );
    }

    @Test
    @DisplayName("opens exact immutable version content when supplied content hash matches")
    void shouldOpenExactVersionContentWhenHashMatches() {
        MediaAssetVersion version = version(2, "local");
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});

        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaAssetStatus.ACTIVE)));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 2))
                .thenReturn(Optional.of(version));
        when(binaryStoragePort.providerId()).thenReturn(StorageProviderId.of("local"));
        when(binaryStoragePort.open(StorageKey.of("objects/chapter-2.mp3"))).thenReturn(stream);

        MediaAssetVersionContentResult result =
                useCase.execute(new OpenMediaAssetVersionContentQuery(ASSET_ID, 2, HASH));

        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.versionNumber()).isEqualTo(2);
        assertThat(result.contentHash()).isEqualTo(HASH);
        assertThat(result.mimeType()).isEqualTo("audio/mpeg");
        assertThat(result.sizeBytes()).isEqualTo(1024L);
        assertThat(result.content()).isSameAs(stream);

        verify(binaryStoragePort).open(StorageKey.of("objects/chapter-2.mp3"));
    }

    @Test
    @DisplayName("opens exact immutable version content for archived assets")
    void shouldOpenExactVersionContentForArchivedAsset() {
        MediaAssetVersion version = version(1, "local");
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{1});

        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaAssetStatus.ARCHIVED)));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1))
                .thenReturn(Optional.of(version));
        when(binaryStoragePort.providerId()).thenReturn(StorageProviderId.of("local"));
        when(binaryStoragePort.open(StorageKey.of("objects/chapter-1.mp3"))).thenReturn(stream);

        MediaAssetVersionContentResult result =
                useCase.execute(new OpenMediaAssetVersionContentQuery(ASSET_ID, 1, HASH));

        assertThat(result.content()).isSameAs(stream);
    }

    @Test
    @DisplayName("deleted asset is hidden from exact version content opening")
    void shouldHideDeletedAsset() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaAssetStatus.DELETED)));

        assertThatThrownBy(() -> useCase.execute(new OpenMediaAssetVersionContentQuery(ASSET_ID, 1, HASH)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(mediaAssetVersionRepositoryPort, never()).findByAssetIdAndVersionNumber(any(), any(Integer.class));
        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("missing exact version throws MediaAssetVersionNotFoundException")
    void shouldThrowWhenExactVersionMissing() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaAssetStatus.ACTIVE)));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 3))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new OpenMediaAssetVersionContentQuery(ASSET_ID, 3, HASH)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("content hash mismatch refuses to open storage")
    void shouldThrowWhenContentHashDoesNotMatch() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaAssetStatus.ACTIVE)));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1))
                .thenReturn(Optional.of(version(1, "local")));

        assertThatThrownBy(() -> useCase.execute(new OpenMediaAssetVersionContentQuery(ASSET_ID, 1, OTHER_HASH)))
                .isInstanceOf(MediaAssetVersionContentHashMismatchException.class);

        verify(binaryStoragePort, never()).open(any());
    }

    @Test
    @DisplayName("storage provider mismatch throws internal StorageException")
    void shouldThrowWhenStorageProviderMismatch() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaAssetStatus.ACTIVE)));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1))
                .thenReturn(Optional.of(version(1, "s3")));
        when(binaryStoragePort.providerId()).thenReturn(StorageProviderId.of("local"));

        assertThatThrownBy(() -> useCase.execute(new OpenMediaAssetVersionContentQuery(ASSET_ID, 1, HASH)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Storage provider mismatch");

        verify(binaryStoragePort, never()).open(any());
    }

    private static MediaAsset asset(
            MediaAssetStatus status
    ) {
        return MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.AUDIO,
                MediaVisibility.RESTRICTED,
                status,
                2,
                T1,
                T1
        );
    }

    private static MediaAssetVersion version(
            int versionNumber,
            String providerId
    ) {
        return MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                versionNumber,
                StorageLocation.of(providerId, "objects/chapter-" + versionNumber + ".mp3"),
                null,
                ContentHash.of(HASH),
                MimeType.of("audio/mpeg"),
                1024L,
                "chapter.mp3",
                T1
        );
    }
}
