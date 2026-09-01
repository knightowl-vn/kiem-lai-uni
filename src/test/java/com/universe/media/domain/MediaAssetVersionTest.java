package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaAssetVersionTest {

    private static final UUID VERSION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final StorageLocation STORAGE_LOCATION =
            StorageLocation.of("cloudinary", "media/assets/2026/09/sample.webp");

    private static final ContentHash CONTENT_HASH =
            ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    private static final MimeType MIME_TYPE =
            MimeType.of("image/webp");

    private static final Instant CREATED_AT =
            Instant.parse("2026-09-01T10:00:00Z");

    @Nested
    @DisplayName("Creation & Invariants")
    class CreationTests {

        @Test
        @DisplayName("create builds an immutable version with all valid fields")
        void shouldCreateVersionWithAllFields() {
            MediaAssetVersion version = MediaAssetVersion.create(
                    VERSION_ID,
                    ASSET_ID,
                    1,
                    STORAGE_LOCATION,
                    "https://cdn.universe.com/media/sample.webp",
                    CONTENT_HASH,
                    MIME_TYPE,
                    1024L,
                    "sample.webp",
                    CREATED_AT
            );

            assertThat(version.getId()).isEqualTo(VERSION_ID);
            assertThat(version.getAssetId()).isEqualTo(ASSET_ID);
            assertThat(version.getVersionNumber()).isEqualTo(1);
            assertThat(version.getStorageLocation()).isEqualTo(STORAGE_LOCATION);
            assertThat(version.getPublicUrl()).isEqualTo("https://cdn.universe.com/media/sample.webp");
            assertThat(version.getContentHash()).isEqualTo(CONTENT_HASH);
            assertThat(version.getMimeType()).isEqualTo(MIME_TYPE);
            assertThat(version.getSizeBytes()).isEqualTo(1024L);
            assertThat(version.getOriginalFilename()).isEqualTo("sample.webp");
            assertThat(version.getCreatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("publicUrl is optional and can be null or blank")
        void shouldAllowNullOrBlankPublicUrl() {
            MediaAssetVersion versionWithNull = MediaAssetVersion.create(
                    VERSION_ID,
                    ASSET_ID,
                    2,
                    STORAGE_LOCATION,
                    null,
                    CONTENT_HASH,
                    MIME_TYPE,
                    2048L,
                    "secret.webp",
                    CREATED_AT
            );
            assertThat(versionWithNull.getPublicUrl()).isNull();

            MediaAssetVersion versionWithBlank = MediaAssetVersion.create(
                    VERSION_ID,
                    ASSET_ID,
                    2,
                    STORAGE_LOCATION,
                    "   ",
                    CONTENT_HASH,
                    MIME_TYPE,
                    2048L,
                    "secret.webp",
                    CREATED_AT
            );
            assertThat(versionWithBlank.getPublicUrl()).isNull();
        }

        @Test
        @DisplayName("rehydrate builds version accurately")
        void shouldRehydrateVersionAccurately() {
            MediaAssetVersion version = MediaAssetVersion.rehydrate(
                    VERSION_ID,
                    ASSET_ID,
                    3,
                    STORAGE_LOCATION,
                    "https://cdn.universe.com/v3.webp",
                    CONTENT_HASH,
                    MIME_TYPE,
                    4096L,
                    "v3.webp",
                    CREATED_AT
            );

            assertThat(version.getVersionNumber()).isEqualTo(3);
            assertThat(version.getSizeBytes()).isEqualTo(4096L);
        }

        @Test
        @DisplayName("create rejects versionNumber < 1")
        void shouldRejectInvalidVersionNumber() {
            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID,
                    ASSET_ID,
                    0,
                    STORAGE_LOCATION,
                    null,
                    CONTENT_HASH,
                    MIME_TYPE,
                    1024L,
                    "sample.webp",
                    CREATED_AT
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Version number must be greater than or equal to 1");
        }

        @Test
        @DisplayName("create rejects sizeBytes <= 0")
        void shouldRejectNonPositiveSizeBytes() {
            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID,
                    ASSET_ID,
                    1,
                    STORAGE_LOCATION,
                    null,
                    CONTENT_HASH,
                    MIME_TYPE,
                    0L,
                    "sample.webp",
                    CREATED_AT
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("File size must be greater than 0 bytes");

            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID,
                    ASSET_ID,
                    1,
                    STORAGE_LOCATION,
                    null,
                    CONTENT_HASH,
                    MIME_TYPE,
                    -10L,
                    "sample.webp",
                    CREATED_AT
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("File size must be greater than 0 bytes");
        }

        @Test
        @DisplayName("create rejects blank originalFilename")
        void shouldRejectBlankOriginalFilename() {
            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID,
                    ASSET_ID,
                    1,
                    STORAGE_LOCATION,
                    null,
                    CONTENT_HASH,
                    MIME_TYPE,
                    1024L,
                    "   ",
                    CREATED_AT
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Original filename cannot be blank");
        }

        @Test
        @DisplayName("create rejects null required arguments")
        void shouldRejectNullRequiredArguments() {
            assertThatThrownBy(() -> MediaAssetVersion.create(
                    null, ASSET_ID, 1, STORAGE_LOCATION, null, CONTENT_HASH, MIME_TYPE, 10L, "f.txt", CREATED_AT
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID, null, 1, STORAGE_LOCATION, null, CONTENT_HASH, MIME_TYPE, 10L, "f.txt", CREATED_AT
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID, ASSET_ID, 1, null, null, CONTENT_HASH, MIME_TYPE, 10L, "f.txt", CREATED_AT
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID, ASSET_ID, 1, STORAGE_LOCATION, null, null, MIME_TYPE, 10L, "f.txt", CREATED_AT
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID, ASSET_ID, 1, STORAGE_LOCATION, null, CONTENT_HASH, null, 10L, "f.txt", CREATED_AT
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> MediaAssetVersion.create(
                    VERSION_ID, ASSET_ID, 1, STORAGE_LOCATION, null, CONTENT_HASH, MIME_TYPE, 10L, "f.txt", null
            )).isInstanceOf(NullPointerException.class);
        }
    }
}
