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
import com.universe.media.domain.StorageLocation;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetCurrentMediaAssetVersionSnapshotUseCaseTest {

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

    private GetCurrentMediaAssetVersionSnapshotUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetCurrentMediaAssetVersionSnapshotUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );
    }

    @Test
    @DisplayName("returns current immutable version snapshot for active asset")
    void shouldReturnCurrentVersionSnapshotForActiveAsset() {
        MediaAsset asset = asset(MediaAssetStatus.ACTIVE, 2);
        MediaAssetVersion version = version(2);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 2))
                .thenReturn(Optional.of(version));

        MediaAssetVersionSnapshotResult result =
                useCase.execute(new GetCurrentMediaAssetVersionSnapshotQuery(ASSET_ID));

        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.versionNumber()).isEqualTo(2);
        assertThat(result.contentHash()).isEqualTo(HASH);
        assertThat(result.mimeType()).isEqualTo("audio/mpeg");
        assertThat(result.sizeBytes()).isEqualTo(1024L);
        assertThat(result.originalFilename()).isEqualTo("chapter.mp3");
    }

    @Test
    @DisplayName("returns current immutable version snapshot for archived asset")
    void shouldReturnCurrentVersionSnapshotForArchivedAsset() {
        MediaAsset asset = asset(MediaAssetStatus.ARCHIVED, 1);
        MediaAssetVersion version = version(1);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1))
                .thenReturn(Optional.of(version));

        MediaAssetVersionSnapshotResult result =
                useCase.execute(new GetCurrentMediaAssetVersionSnapshotQuery(ASSET_ID));

        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.versionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("deleted asset is hidden from internal version snapshot lookup")
    void shouldHideDeletedAsset() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaAssetStatus.DELETED, 1)));

        assertThatThrownBy(() -> useCase.execute(new GetCurrentMediaAssetVersionSnapshotQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);

        verify(mediaAssetVersionRepositoryPort, never()).findByAssetIdAndVersionNumber(any(), any(Integer.class));
    }

    @Test
    @DisplayName("missing current version remains a data integrity failure")
    void shouldThrowWhenCurrentVersionMissing() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset(MediaAssetStatus.ACTIVE, 3)));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 3))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetCurrentMediaAssetVersionSnapshotQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);
    }

    private static MediaAsset asset(
            MediaAssetStatus status,
            int currentVersionNumber
    ) {
        return MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.AUDIO,
                MediaVisibility.RESTRICTED,
                status,
                currentVersionNumber,
                T1,
                T1
        );
    }

    private static MediaAssetVersion version(
            int versionNumber
    ) {
        return MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                versionNumber,
                StorageLocation.of("local", "objects/chapter-" + versionNumber + ".mp3"),
                null,
                ContentHash.of(HASH),
                MimeType.of("audio/mpeg"),
                1024L,
                "chapter.mp3",
                T1
        );
    }
}
