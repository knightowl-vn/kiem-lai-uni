package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageLocationTest {

    @Nested
    @DisplayName("StorageProviderId Tests")
    class StorageProviderIdTests {

        @Test
        @DisplayName("valid provider ID is trimmed, lowercased, and accepted")
        void shouldAcceptValidStorageProviderId() {
            StorageProviderId id = StorageProviderId.of(" Cloudinary-Main ");
            assertThat(id.value()).isEqualTo("cloudinary-main");
            assertThat(id.toString()).isEqualTo("cloudinary-main");

            StorageProviderId s3Id = StorageProviderId.of("s3_backup");
            assertThat(s3Id.value()).isEqualTo("s3_backup");
        }

        @Test
        @DisplayName("StorageProviderId rejects null or blank")
        void shouldRejectNullOrBlank() {
            assertThatThrownBy(() -> StorageProviderId.of(null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> StorageProviderId.of("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("StorageProviderId cannot be blank");
        }

        @Test
        @DisplayName("StorageProviderId rejects exceeding max length (50)")
        void shouldRejectExceedingMaxLength() {
            String tooLong = "a".repeat(51);
            assertThatThrownBy(() -> StorageProviderId.of(tooLong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed 50 characters");
        }

        @Test
        @DisplayName("StorageProviderId rejects invalid characters")
        void shouldRejectInvalidCharacters() {
            assertThatThrownBy(() -> StorageProviderId.of("invalid/slash"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must contain only lowercase letters, digits, dashes, and underscores");

            assertThatThrownBy(() -> StorageProviderId.of("invalid.dot"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must contain only lowercase letters, digits, dashes, and underscores");
        }
    }

    @Nested
    @DisplayName("StorageKey Tests")
    class StorageKeyTests {

        @Test
        @DisplayName("valid storage key preserves opaque characters exactly, including whitespace")
        void shouldAcceptAndPreserveOpaqueStorageKey() {
            StorageKey key = StorageKey.of("  media/assets/covers/file.webp  ");
            assertThat(key.value()).isEqualTo("  media/assets/covers/file.webp  ");
            assertThat(key.toString()).isEqualTo("  media/assets/covers/file.webp  ");
        }

        @Test
        @DisplayName("valid storage key accepts double-dot filenames")
        void shouldAcceptDoubleDotsInStorageKey() {
            StorageKey key = StorageKey.of("media/file..name.jpg");
            assertThat(key.value()).isEqualTo("media/file..name.jpg");
        }

        @Test
        @DisplayName("storage key preserves backslashes rather than rewriting them")
        void shouldPreserveBackslashesInStorageKey() {
            StorageKey key = StorageKey.of("media\\custom\\key.bin");
            assertThat(key.value()).isEqualTo("media\\custom\\key.bin");
        }

        @Test
        @DisplayName("StorageKey rejects null or blank")
        void shouldRejectNullOrBlank() {
            assertThatThrownBy(() -> StorageKey.of(null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> StorageKey.of("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("StorageKey cannot be blank");
        }

        @Test
        @DisplayName("StorageKey rejects exceeding max length (500)")
        void shouldRejectExceedingMaxLength() {
            String tooLong = "a".repeat(501);
            assertThatThrownBy(() -> StorageKey.of(tooLong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed 500 characters");
        }
    }

    @Nested
    @DisplayName("StorageLocation Tests")
    class StorageLocationTests {

        @Test
        @DisplayName("StorageLocation combines providerId and key")
        void shouldCreateStorageLocation() {
            StorageLocation loc = StorageLocation.of("r2", "path/to/asset.png");
            assertThat(loc.providerId().value()).isEqualTo("r2");
            assertThat(loc.key().value()).isEqualTo("path/to/asset.png");
        }

        @Test
        @DisplayName("StorageLocation rejects null components")
        void shouldRejectNullComponents() {
            StorageKey key = StorageKey.of("key");
            StorageProviderId provider = StorageProviderId.of("provider");

            assertThatThrownBy(() -> new StorageLocation(null, key))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new StorageLocation(provider, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
