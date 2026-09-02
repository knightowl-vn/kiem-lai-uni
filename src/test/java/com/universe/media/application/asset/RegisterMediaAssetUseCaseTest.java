package com.universe.media.application.asset;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageLocation;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterMediaAssetUseCaseTest {

    private static final Instant FIXED_NOW =
            Instant.parse("2026-09-01T12:00:00Z");

    private static final ClockPort FIXED_CLOCK =
            () -> FIXED_NOW;

    private static final String VALID_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    private RegisterMediaAssetUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RegisterMediaAssetUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                FIXED_CLOCK
        );
    }

    private RegisterMediaAssetCommand createValidCommand() {
        return new RegisterMediaAssetCommand(
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                "cloudinary",
                "covers/novel-cover.webp",
                "https://cdn.universe.com/covers/novel-cover.webp",
                VALID_HASH,
                "image/webp",
                2048L,
                "novel-cover.webp"
        );
    }

    @Test
    @DisplayName("successful registration creates and persists MediaAsset and MediaAssetVersion v1")
    void shouldRegisterInitialMediaAssetSuccessfully() {
        RegisterMediaAssetCommand command = createValidCommand();
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class)))
                .thenReturn(false);

        RegisterMediaAssetResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.assetId()).isNotNull();
        assertThat(result.versionId()).isNotNull();
        assertThat(result.assetId()).isNotEqualTo(result.versionId());
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(result.visibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(result.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(result.createdAt()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<MediaAsset> assetCaptor = ArgumentCaptor.forClass(MediaAsset.class);
        ArgumentCaptor<MediaAssetVersion> versionCaptor = ArgumentCaptor.forClass(MediaAssetVersion.class);

        verify(mediaAssetRepositoryPort).save(assetCaptor.capture());
        verify(mediaAssetVersionRepositoryPort).save(versionCaptor.capture());

        MediaAsset savedAsset = assetCaptor.getValue();
        assertThat(savedAsset.getId()).isEqualTo(result.assetId());
        assertThat(savedAsset.getMediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(savedAsset.getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(savedAsset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(savedAsset.getCurrentVersionNumber()).isEqualTo(1);
        assertThat(savedAsset.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(savedAsset.getUpdatedAt()).isEqualTo(FIXED_NOW);

        MediaAssetVersion savedVersion = versionCaptor.getValue();
        assertThat(savedVersion.getId()).isEqualTo(result.versionId());
        assertThat(savedVersion.getAssetId()).isEqualTo(result.assetId());
        assertThat(savedVersion.getVersionNumber()).isEqualTo(1);
        assertThat(savedVersion.getStorageLocation().providerId().value()).isEqualTo("cloudinary");
        assertThat(savedVersion.getStorageLocation().key().value()).isEqualTo("covers/novel-cover.webp");
        assertThat(savedVersion.getPublicUrl()).isEqualTo("https://cdn.universe.com/covers/novel-cover.webp");
        assertThat(savedVersion.getContentHash().value()).isEqualTo(VALID_HASH);
        assertThat(savedVersion.getMimeType().value()).isEqualTo("image/webp");
        assertThat(savedVersion.getSizeBytes()).isEqualTo(2048L);
        assertThat(savedVersion.getOriginalFilename()).isEqualTo("novel-cover.webp");
        assertThat(savedVersion.getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("duplicate StorageLocation throws DuplicateStorageLocationException and performs no saves")
    void shouldRejectDuplicateStorageLocation() {
        RegisterMediaAssetCommand command = createValidCommand();
        when(mediaAssetVersionRepositoryPort.existsByStorageLocation(any(StorageLocation.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(DuplicateStorageLocationException.class)
                .hasMessageContaining("cloudinary")
                .hasMessageContaining("covers/novel-cover.webp");

        verifyNoInteractions(mediaAssetRepositoryPort);
        verify(mediaAssetVersionRepositoryPort, never()).save(any(MediaAssetVersion.class));
    }
}
