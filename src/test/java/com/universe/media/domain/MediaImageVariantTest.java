package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaImageVariantTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final StorageLocation STORAGE_LOCATION = StorageLocation.of("local", "objects/variants/thumb.png");
    private static final ContentHash CONTENT_HASH = ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    private static final MimeType MIME_TYPE = MimeType.of("image/png");
    private static final Instant CREATED_AT = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    @DisplayName("creates valid physical MediaImageVariant from ImageVariantSpec")
    void shouldCreateValidPhysicalMediaImageVariant() {
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        MediaImageVariant variant = MediaImageVariant.create(
                ID,
                VERSION_ID,
                spec,
                STORAGE_LOCATION,
                CONTENT_HASH,
                MIME_TYPE,
                15000L,
                300,
                450,
                CREATED_AT
        );

        assertThat(variant.getId()).isEqualTo(ID);
        assertThat(variant.getVersionId()).isEqualTo(VERSION_ID);
        assertThat(variant.getVariantKey()).isEqualTo("w300");
        assertThat(variant.getTargetWidth()).isEqualTo(300);
        assertThat(variant.getStorageLocation()).isEqualTo(STORAGE_LOCATION);
        assertThat(variant.getPublicUrl()).isNull();
        assertThat(variant.hasPublicUrl()).isFalse();
        assertThat(variant.isPhysical()).isTrue();
        assertThat(variant.isVirtual()).isFalse();
        assertThat(variant.getContentHash()).isEqualTo(CONTENT_HASH);
        assertThat(variant.getMimeType()).isEqualTo(MIME_TYPE);
        assertThat(variant.getSizeBytes()).isEqualTo(15000L);
        assertThat(variant.getWidth()).isEqualTo(300);
        assertThat(variant.getHeight()).isEqualTo(450);
        assertThat(variant.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("creates valid external virtual MediaImageVariant with publicUrl and null StorageLocation")
    void shouldCreateValidExternalVirtualVariant() {
        ImageVariantSpec spec = ImageVariantSpec.of(400);
        String publicUrl = "https://res.cloudinary.com/test/image/upload/c_scale,w_400/kiemlai/wiki/covers/great-ruler.webp";

        MediaImageVariant variant = MediaImageVariant.createExternal(
                ID,
                VERSION_ID,
                spec,
                publicUrl,
                MIME_TYPE,
                CREATED_AT
        );

        assertThat(variant.getId()).isEqualTo(ID);
        assertThat(variant.getVersionId()).isEqualTo(VERSION_ID);
        assertThat(variant.getVariantKey()).isEqualTo("w400");
        assertThat(variant.getTargetWidth()).isEqualTo(400);
        assertThat(variant.getStorageLocation()).isNull();
        assertThat(variant.getPublicUrl()).isEqualTo(publicUrl);
        assertThat(variant.hasPublicUrl()).isTrue();
        assertThat(variant.isVirtual()).isTrue();
        assertThat(variant.isPhysical()).isFalse();
        assertThat(variant.getContentHash()).isNull();
        assertThat(variant.getSizeBytes()).isNull();
        assertThat(variant.getWidth()).isNull();
        assertThat(variant.getHeight()).isNull();
        assertThat(variant.getMimeType()).isEqualTo(MIME_TYPE);
        assertThat(variant.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("enforces XOR invariant: fails when both storageLocation and publicUrl are present or both are absent")
    void shouldEnforceXorStorageInvariant() {
        String publicUrl = "https://res.cloudinary.com/test/image/upload/c_scale,w_400/cover.webp";

        // Both present
        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, "w400", 400, STORAGE_LOCATION, publicUrl, CONTENT_HASH, MIME_TYPE, 1000L, 400, 300, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Variant cannot have both a physical StorageLocation and an external publicUrl");

        // Both absent
        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, "w400", 400, null, null, null, MIME_TYPE, null, null, null, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Variant must have either a physical StorageLocation or an external publicUrl");
    }

    @Test
    @DisplayName("validates HTTPS scheme, absolute URI, and non-blank URL on external variant")
    void shouldValidateHttpsPublicUrl() {
        ImageVariantSpec spec = ImageVariantSpec.of(400);

        // Blank URL
        assertThatThrownBy(() -> MediaImageVariant.createExternal(ID, VERSION_ID, spec, "  ", MIME_TYPE, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("External variant publicUrl cannot be blank");

        // Null URL
        assertThatThrownBy(() -> MediaImageVariant.createExternal(ID, VERSION_ID, spec, null, MIME_TYPE, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("External variant publicUrl cannot be null");

        // HTTP (non-HTTPS) scheme
        assertThatThrownBy(() -> MediaImageVariant.createExternal(
                ID, VERSION_ID, spec, "http://insecure.example.com/image.webp", MIME_TYPE, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("External variant publicUrl must use HTTPS scheme");

        // Relative URI
        assertThatThrownBy(() -> MediaImageVariant.createExternal(
                ID, VERSION_ID, spec, "/local/path/image.webp", MIME_TYPE, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("External variant publicUrl must be an absolute URI with a host");

        // Malformed URI syntax
        assertThatThrownBy(() -> MediaImageVariant.createExternal(
                ID, VERSION_ID, spec, "https://invalid url with spaces.com", MIME_TYPE, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid external variant publicUrl");

        // Exceeding 1000 chars
        String longUrl = "https://example.com/" + "a".repeat(1000);
        assertThatThrownBy(() -> MediaImageVariant.createExternal(
                ID, VERSION_ID, spec, longUrl, MIME_TYPE, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("External variant publicUrl cannot exceed 1000 characters");
    }

    @Test
    @DisplayName("rehydrates valid physical MediaImageVariant")
    void shouldRehydrateValidPhysicalMediaImageVariant() {
        MediaImageVariant variant = MediaImageVariant.rehydrate(
                ID,
                VERSION_ID,
                "w800",
                800,
                STORAGE_LOCATION,
                CONTENT_HASH,
                MIME_TYPE,
                45000L,
                800,
                1200,
                CREATED_AT
        );

        assertThat(variant.getId()).isEqualTo(ID);
        assertThat(variant.getVariantKey()).isEqualTo("w800");
        assertThat(variant.getTargetWidth()).isEqualTo(800);
        assertThat(variant.getWidth()).isEqualTo(800);
        assertThat(variant.getHeight()).isEqualTo(1200);
        assertThat(variant.getStorageLocation()).isEqualTo(STORAGE_LOCATION);
        assertThat(variant.getPublicUrl()).isNull();
        assertThat(variant.isPhysical()).isTrue();
    }

    @Test
    @DisplayName("rehydrates valid external virtual MediaImageVariant with publicUrl")
    void shouldRehydrateValidExternalVirtualVariant() {
        String publicUrl = "https://res.cloudinary.com/test/image/upload/c_scale,w_400/kiemlai/wiki/covers/great-ruler.webp";

        MediaImageVariant variant = MediaImageVariant.rehydrate(
                ID,
                VERSION_ID,
                "w400",
                400,
                null,
                publicUrl,
                null,
                MIME_TYPE,
                null,
                null,
                null,
                CREATED_AT
        );

        assertThat(variant.getId()).isEqualTo(ID);
        assertThat(variant.getVariantKey()).isEqualTo("w400");
        assertThat(variant.getStorageLocation()).isNull();
        assertThat(variant.getPublicUrl()).isEqualTo(publicUrl);
        assertThat(variant.hasPublicUrl()).isTrue();
        assertThat(variant.isVirtual()).isTrue();
        assertThat(variant.isPhysical()).isFalse();
        assertThat(variant.getContentHash()).isNull();
    }

    @Test
    @DisplayName("rejects null required fields for physical variant")
    void shouldRejectNullRequiredFieldsForPhysicalVariant() {
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        assertThatThrownBy(() -> MediaImageVariant.create(null, VERSION_ID, spec, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Variant ID cannot be null");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, null, spec, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Version ID cannot be null");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, null, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ImageVariantSpec cannot be null");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, null, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StorageLocation cannot be null");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, STORAGE_LOCATION, null, MIME_TYPE, 1000L, 300, 450, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ContentHash cannot be null");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, STORAGE_LOCATION, CONTENT_HASH, null, 1000L, 300, 450, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MimeType cannot be null");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CreatedAt timestamp cannot be null");
    }

    @Test
    @DisplayName("rejects non-positive sizes and dimensions for physical variant")
    void shouldRejectNonPositiveSizesAndDimensions() {
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 0L, 300, 450, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes must be greater than 0");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, -5L, 300, 450, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes must be greater than 0");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 0, 450, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width must be greater than 0");

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 0, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("height must be greater than 0");
    }

    @Test
    @DisplayName("rejects non-canonical, untrimmed, or mismatched variantKey in rehydrate")
    void shouldRejectMismatchedVariantKeyInRehydrate() {
        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, "w500", 300, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match expected canonical key 'w300'");

        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, " w300", 300, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match expected canonical key 'w300'");

        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, "w300 ", 300, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match expected canonical key 'w300'");

        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, " W300 ", 300, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match expected canonical key 'w300'");

        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, "W300", 300, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match expected canonical key 'w300'");
    }

    @Test
    @DisplayName("rejects width exceeding targetWidth in create and rehydrate")
    void shouldRejectWidthExceedingTargetWidth() {
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        assertThatThrownBy(() -> MediaImageVariant.create(ID, VERSION_ID, spec, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 301, 450, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed targetWidth");

        assertThatThrownBy(() -> MediaImageVariant.rehydrate(ID, VERSION_ID, "w300", 300, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 301, 450, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed targetWidth");
    }

    @Test
    @DisplayName("accepts actual width smaller than targetWidth")
    void shouldAcceptActualWidthSmallerThanTargetWidth() {
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        MediaImageVariant variant = MediaImageVariant.create(
                ID,
                VERSION_ID,
                spec,
                STORAGE_LOCATION,
                CONTENT_HASH,
                MIME_TYPE,
                10000L,
                200,
                300,
                CREATED_AT
        );

        assertThat(variant.getWidth()).isEqualTo(200);
        assertThat(variant.getTargetWidth()).isEqualTo(300);

        MediaImageVariant rehydrated = MediaImageVariant.rehydrate(
                ID,
                VERSION_ID,
                "w300",
                300,
                STORAGE_LOCATION,
                CONTENT_HASH,
                MIME_TYPE,
                10000L,
                200,
                300,
                CREATED_AT
        );

        assertThat(rehydrated.getWidth()).isEqualTo(200);
        assertThat(rehydrated.getTargetWidth()).isEqualTo(300);
    }

    @Test
    @DisplayName("rejects targetWidth outside [16, 7680] in create and rehydrate")
    void shouldRejectTargetWidthOutsideBoundsInCreateAndRehydrate() {
        assertThatThrownBy(() -> ImageVariantSpec.of(15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetWidth must be between 16 and 7680");

        assertThatThrownBy(() -> ImageVariantSpec.of(7681))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetWidth must be between 16 and 7680");

        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, "w15", 15, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 15, 20, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetWidth must be between 16 and 7680");

        assertThatThrownBy(() -> MediaImageVariant.rehydrate(
                ID, VERSION_ID, "w7681", 7681, STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 7681, 1000, CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetWidth must be between 16 and 7680");
    }

    @Test
    @DisplayName("satisfies entity equality based on ID")
    void shouldSatisfyEntityEquality() {
        MediaImageVariant v1 = MediaImageVariant.create(ID, VERSION_ID, ImageVariantSpec.of(300), STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT);
        MediaImageVariant v2 = MediaImageVariant.create(ID, VERSION_ID, ImageVariantSpec.of(300), STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 2000L, 300, 450, CREATED_AT);
        MediaImageVariant v3 = MediaImageVariant.create(UUID.randomUUID(), VERSION_ID, ImageVariantSpec.of(300), STORAGE_LOCATION, CONTENT_HASH, MIME_TYPE, 1000L, 300, 450, CREATED_AT);

        assertThat(v1).isEqualTo(v2);
        assertThat(v1.hashCode()).isEqualTo(v2.hashCode());
        assertThat(v1).isNotEqualTo(v3);
    }
}
