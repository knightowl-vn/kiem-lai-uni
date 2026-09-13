package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FindMediaVersionsByContentHashUseCaseTest {

    private static final String VALID_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final Instant T1 =
            Instant.parse("2026-09-01T10:00:00Z");

    private static final Instant T2 =
            Instant.parse("2026-09-01T11:00:00Z");

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    private FindMediaVersionsByContentHashUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new FindMediaVersionsByContentHashUseCase(
                mediaAssetVersionRepositoryPort
        );
    }

    @Test
    @DisplayName("multiple matching versions are returned and mapped accurately preserving order")
    void shouldReturnAllMatchingVersions() {
        UUID assetId1 = UUID.randomUUID();
        UUID versionId1 = UUID.randomUUID();

        UUID assetId2 = UUID.randomUUID();
        UUID versionId2 = UUID.randomUUID();

        MediaAssetVersion v1 = MediaAssetVersion.create(
                versionId1,
                assetId1,
                1,
                StorageLocation.of("cloudinary", "covers/v1.webp"),
                "https://cdn.universe.com/v1.webp",
                ContentHash.of(VALID_HASH),
                MimeType.of("image/webp"),
                1024L,
                "v1.webp",
                T1
        );

        MediaAssetVersion v2 = MediaAssetVersion.create(
                versionId2,
                assetId2,
                1,
                StorageLocation.of("s3", "avatars/v2.webp"),
                null,
                ContentHash.of(VALID_HASH),
                MimeType.of("image/webp"),
                1024L,
                "v2.webp",
                T2
        );

        when(mediaAssetVersionRepositoryPort.findByContentHash(any(ContentHash.class)))
                .thenReturn(List.of(v1, v2));

        List<MediaVersionItemResult> results = useCase.execute(
                new FindMediaVersionsByContentHashQuery(VALID_HASH)
        );

        ArgumentCaptor<ContentHash> hashCaptor = ArgumentCaptor.forClass(ContentHash.class);
        verify(mediaAssetVersionRepositoryPort).findByContentHash(hashCaptor.capture());
        assertThat(hashCaptor.getValue().value()).isEqualTo(VALID_HASH);

        assertThat(results).hasSize(2);

        MediaVersionItemResult item1 = results.get(0);
        assertThat(item1.id()).isEqualTo(versionId1);
        assertThat(item1.assetId()).isEqualTo(assetId1);
        assertThat(item1.versionNumber()).isEqualTo(1);
        assertThat(item1.storageProviderId()).isEqualTo("cloudinary");
        assertThat(item1.storageKey()).isEqualTo("covers/v1.webp");
        assertThat(item1.publicUrl()).isEqualTo("https://cdn.universe.com/v1.webp");
        assertThat(item1.contentHash()).isEqualTo(VALID_HASH);
        assertThat(item1.sizeBytes()).isEqualTo(1024L);
        assertThat(item1.createdAt()).isEqualTo(T1);

        MediaVersionItemResult item2 = results.get(1);
        assertThat(item2.id()).isEqualTo(versionId2);
        assertThat(item2.assetId()).isEqualTo(assetId2);
        assertThat(item2.versionNumber()).isEqualTo(1);
        assertThat(item2.storageProviderId()).isEqualTo("s3");
        assertThat(item2.storageKey()).isEqualTo("avatars/v2.webp");
        assertThat(item2.publicUrl()).isNull();
        assertThat(item2.contentHash()).isEqualTo(VALID_HASH);
        assertThat(item2.sizeBytes()).isEqualTo(1024L);
        assertThat(item2.createdAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("empty repository result returns empty list")
    void shouldReturnEmptyListWhenNoMatches() {
        when(mediaAssetVersionRepositoryPort.findByContentHash(any(ContentHash.class)))
                .thenReturn(List.of());

        List<MediaVersionItemResult> results = useCase.execute(
                new FindMediaVersionsByContentHashQuery(VALID_HASH)
        );

        assertThat(results).isNotNull().isEmpty();
    }
}
