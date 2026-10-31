package com.universe.media.infrastructure.storage.r2movie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class R2MovieStoragePropertiesTest {

    private static final String VALID_BUCKET = "my-r2-movie-bucket";
    private static final String VALID_ENDPOINT = "https://test-account.r2.cloudflarestorage.com";
    private static final String VALID_ACCESS_KEY = "test-access-key";
    private static final String VALID_SECRET_KEY = "test-secret-key";

    @Test
    @DisplayName("constructs successfully with valid properties and trims values")
    void shouldConstructWithValidProperties() {
        R2MovieStorageProperties properties = new R2MovieStorageProperties(
                "  " + VALID_BUCKET + "  ",
                "  " + VALID_ENDPOINT + "  ",
                "  " + VALID_ACCESS_KEY + "  ",
                "  " + VALID_SECRET_KEY + "  ",
                "  auto  "
        );

        assertThat(properties.bucket()).isEqualTo(VALID_BUCKET);
        assertThat(properties.endpoint()).isEqualTo(VALID_ENDPOINT);
        assertThat(properties.accessKeyId()).isEqualTo(VALID_ACCESS_KEY);
        assertThat(properties.secretAccessKey()).isEqualTo(VALID_SECRET_KEY);
        assertThat(properties.region()).isEqualTo("auto");
    }

    @Test
    @DisplayName("defaults region to auto when null or blank")
    void shouldDefaultRegionToAuto() {
        R2MovieStorageProperties propNull = new R2MovieStorageProperties(
                VALID_BUCKET, VALID_ENDPOINT, VALID_ACCESS_KEY, VALID_SECRET_KEY, null
        );
        assertThat(propNull.region()).isEqualTo("auto");

        R2MovieStorageProperties propBlank = new R2MovieStorageProperties(
                VALID_BUCKET, VALID_ENDPOINT, VALID_ACCESS_KEY, VALID_SECRET_KEY, "   "
        );
        assertThat(propBlank.region()).isEqualTo("auto");
    }

    @Test
    @DisplayName("throws IllegalStateException when required properties are blank or null")
    void shouldValidateRequiredProperties() {
        assertThatThrownBy(() -> new R2MovieStorageProperties(null, VALID_ENDPOINT, VALID_ACCESS_KEY, VALID_SECRET_KEY, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.bucket");

        assertThatThrownBy(() -> new R2MovieStorageProperties("   ", VALID_ENDPOINT, VALID_ACCESS_KEY, VALID_SECRET_KEY, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.bucket");

        assertThatThrownBy(() -> new R2MovieStorageProperties(VALID_BUCKET, null, VALID_ACCESS_KEY, VALID_SECRET_KEY, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.endpoint");

        assertThatThrownBy(() -> new R2MovieStorageProperties(VALID_BUCKET, "   ", VALID_ACCESS_KEY, VALID_SECRET_KEY, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.endpoint");

        assertThatThrownBy(() -> new R2MovieStorageProperties(VALID_BUCKET, VALID_ENDPOINT, null, VALID_SECRET_KEY, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.access-key-id");

        assertThatThrownBy(() -> new R2MovieStorageProperties(VALID_BUCKET, VALID_ENDPOINT, "  ", VALID_SECRET_KEY, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.access-key-id");

        assertThatThrownBy(() -> new R2MovieStorageProperties(VALID_BUCKET, VALID_ENDPOINT, VALID_ACCESS_KEY, null, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.secret-access-key");

        assertThatThrownBy(() -> new R2MovieStorageProperties(VALID_BUCKET, VALID_ENDPOINT, VALID_ACCESS_KEY, "  ", "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.secret-access-key");
    }

    @Test
    @DisplayName("throws IllegalStateException on invalid endpoint URI")
    void shouldValidateEndpointUri() {
        assertThatThrownBy(() -> new R2MovieStorageProperties(VALID_BUCKET, "not-a-valid-uri", VALID_ACCESS_KEY, VALID_SECRET_KEY, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cloudflare R2 Movie endpoint must be a valid absolute URI");

        assertThatThrownBy(() -> new R2MovieStorageProperties(VALID_BUCKET, "http://", VALID_ACCESS_KEY, VALID_SECRET_KEY, "auto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cloudflare R2 Movie endpoint must be a valid URI");
    }

    @Test
    @DisplayName("toString masks credentials")
    void shouldMaskCredentialsInToString() {
        R2MovieStorageProperties properties = new R2MovieStorageProperties(
                VALID_BUCKET,
                VALID_ENDPOINT,
                VALID_ACCESS_KEY,
                VALID_SECRET_KEY,
                "auto"
        );

        String str = properties.toString();
        assertThat(str).contains("accessKeyId='[PROTECTED]'");
        assertThat(str).contains("secretAccessKey='[PROTECTED]'");
        assertThat(str).doesNotContain(VALID_ACCESS_KEY);
        assertThat(str).doesNotContain(VALID_SECRET_KEY);
        assertThat(str).contains(VALID_BUCKET);
        assertThat(str).contains(VALID_ENDPOINT);
        assertThat(str).contains("auto");
    }
}
