package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MediaAssetVersion ContentHash Provenance Unit Test")
class MediaAssetVersionHashProvenanceTest {

    private final UUID id = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final StorageLocation storageLocation = StorageLocation.of(StorageProviderId.of("b2"), StorageKey.of("objects/v1.mp4"));
    private final ContentHash contentHash = ContentHash.of("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    private final MimeType mimeType = MimeType.of("video/mp4");
    private final Instant now = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    @DisplayName("Should create MediaAssetVersion with SERVER_COMPUTED hash status")
    void shouldCreateWithServerComputedStatus() {
        MediaAssetVersion version = MediaAssetVersion.create(
                id,
                assetId,
                1,
                storageLocation,
                null,
                contentHash,
                ContentHashStatus.SERVER_COMPUTED,
                mimeType,
                1024L,
                "video.mp4",
                now
        );

        assertThat(version.getContentHashStatus()).isEqualTo(ContentHashStatus.SERVER_COMPUTED);
    }

    @Test
    @DisplayName("Should create MediaAssetVersion with CLIENT_DECLARED_UNVERIFIED hash status")
    void shouldCreateWithClientDeclaredStatus() {
        MediaAssetVersion version = MediaAssetVersion.create(
                id,
                assetId,
                1,
                storageLocation,
                null,
                contentHash,
                ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                mimeType,
                1024L,
                "video.mp4",
                now
        );

        assertThat(version.getContentHashStatus()).isEqualTo(ContentHashStatus.CLIENT_DECLARED_UNVERIFIED);
    }

    @Test
    @DisplayName("Legacy create overload defaults to null provenance for historical safety and truth-preservation")
    void shouldSupportLegacyOverloadsAndNullStatus() {
        MediaAssetVersion legacyCreated = MediaAssetVersion.create(
                id,
                assetId,
                1,
                storageLocation,
                null,
                contentHash,
                mimeType,
                1024L,
                "video.mp4",
                now
        );
        assertThat(legacyCreated.getContentHashStatus()).isNull();

        MediaAssetVersion legacyRehydrated = MediaAssetVersion.rehydrate(
                id,
                assetId,
                1,
                storageLocation,
                null,
                contentHash,
                null,
                mimeType,
                1024L,
                "video.mp4",
                now
        );
        assertThat(legacyRehydrated.getContentHashStatus()).isNull();
    }
}
