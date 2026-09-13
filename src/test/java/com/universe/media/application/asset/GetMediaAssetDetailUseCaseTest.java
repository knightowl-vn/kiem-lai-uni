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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMediaAssetDetailUseCaseTest {

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VERSION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Instant T1 =
            Instant.parse("2026-09-01T10:00:00Z");

    private static final Instant T2 =
            Instant.parse("2026-09-01T12:00:00Z");

    private static final String VALID_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    private GetMediaAssetDetailUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetMediaAssetDetailUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );
    }

    @Test
    @DisplayName("successful detail retrieval returns asset summary with current version")
    void shouldReturnAssetDetailWithCurrentVersion() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.ACTIVE,
                2,
                T1,
                T2
        );

        MediaAssetVersion version = MediaAssetVersion.create(
                VERSION_ID,
                ASSET_ID,
                2,
                StorageLocation.of("cloudinary", "covers/novel-v2.webp"),
                "https://cdn.universe.com/covers/novel-v2.webp",
                ContentHash.of(VALID_HASH),
                MimeType.of("image/webp"),
                4096L,
                "novel-v2.webp",
                T2
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 2))
                .thenReturn(Optional.of(version));

        MediaAssetDetailResult result = useCase.execute(new GetMediaAssetDetailQuery(ASSET_ID));

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(ASSET_ID);
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(result.visibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(result.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(result.currentVersionNumber()).isEqualTo(2);
        assertThat(result.createdAt()).isEqualTo(T1);
        assertThat(result.updatedAt()).isEqualTo(T2);

        MediaVersionItemResult versionItem = result.currentVersion();
        assertThat(versionItem).isNotNull();
        assertThat(versionItem.id()).isEqualTo(VERSION_ID);
        assertThat(versionItem.assetId()).isEqualTo(ASSET_ID);
        assertThat(versionItem.versionNumber()).isEqualTo(2);
        assertThat(versionItem.storageProviderId()).isEqualTo("cloudinary");
        assertThat(versionItem.storageKey()).isEqualTo("covers/novel-v2.webp");
        assertThat(versionItem.publicUrl()).isEqualTo("https://cdn.universe.com/covers/novel-v2.webp");
        assertThat(versionItem.contentHash()).isEqualTo(VALID_HASH);
        assertThat(versionItem.mimeType()).isEqualTo("image/webp");
        assertThat(versionItem.sizeBytes()).isEqualTo(4096L);
        assertThat(versionItem.originalFilename()).isEqualTo("novel-v2.webp");
        assertThat(versionItem.createdAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("asset not found throws MediaAssetNotFoundException")
    void shouldThrowWhenAssetNotFound() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetDetailQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class)
                .hasMessageContaining(ASSET_ID.toString());

        verifyNoInteractions(mediaAssetVersionRepositoryPort);
    }

    @Test
    @DisplayName("missing current version throws MediaAssetVersionNotFoundException")
    void shouldThrowWhenCurrentVersionMissing() {
        MediaAsset asset = MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T1
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID))
                .thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new GetMediaAssetDetailQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class)
                .hasMessageContaining(ASSET_ID.toString())
                .hasMessageContaining("1");
    }
}
