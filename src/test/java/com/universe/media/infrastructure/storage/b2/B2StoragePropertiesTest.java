package com.universe.media.infrastructure.storage.b2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class B2StoragePropertiesTest {

    private static final String VALID_BUCKET = "my-b2-bucket";
    private static final String VALID_ENDPOINT = "https://s3.us-east-005.backblazeb2.com";
    private static final String VALID_ACCESS_KEY = "test-access-key";
    private static final String VALID_SECRET_KEY = "test-secret-key";

    @Test
    @DisplayName("constructs successfully with valid properties and trims values")
    void shouldConstructWithValidProperties() {
        B2StorageProperties properties = new B2StorageProperties(
                "  " + VALID_BUCKET + "  ",
                "  " + VALID_ENDPOINT + "  ",
                "  " + VALID_ACCESS_KEY + "  ",
                "  " + VALID_SECRET_KEY + "  ",
                "  us-east-005  "
        );

        assertThat(properties.bucket()).isEqualTo(VALID_BUCKET);
        assertThat(properties.endpoint()).isEqualTo(VALID_ENDPOINT);
        assertThat(properties.accessKeyId()).isEqualTo(VALID_ACCESS_KEY);
        assertThat(properties.secretAccessKey()).isEqualTo(VALID_SECRET_KEY);
        assertThat(properties.region()).isEqualTo("us-east-005");
    }

    @Test
    @DisplayName("throws IllegalStateException when required properties are blank or null")
    void shouldValidateRequiredProperties() {
        assertThatThrownBy(() -> new B2StorageProperties(null, VALID_ENDPOINT, VALID_ACCESS_KEY, VALID_SECRET_KEY, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.bucket");

        assertThatThrownBy(() -> new B2StorageProperties("   ", VALID_ENDPOINT, VALID_ACCESS_KEY, VALID_SECRET_KEY, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.bucket");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, null, VALID_ACCESS_KEY, VALID_SECRET_KEY, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.endpoint");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, "   ", VALID_ACCESS_KEY, VALID_SECRET_KEY, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.endpoint");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, VALID_ENDPOINT, null, VALID_SECRET_KEY, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.access-key-id");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, VALID_ENDPOINT, "  ", VALID_SECRET_KEY, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.access-key-id");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, VALID_ENDPOINT, VALID_ACCESS_KEY, null, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.secret-access-key");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, VALID_ENDPOINT, VALID_ACCESS_KEY, "  ", "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.secret-access-key");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, VALID_ENDPOINT, VALID_ACCESS_KEY, VALID_SECRET_KEY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.region");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, VALID_ENDPOINT, VALID_ACCESS_KEY, VALID_SECRET_KEY, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.b2.region");
    }

    @Test
    @DisplayName("throws IllegalStateException on invalid endpoint URI")
    void shouldValidateEndpointUri() {
        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, "not-a-valid-uri", VALID_ACCESS_KEY, VALID_SECRET_KEY, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Backblaze B2 endpoint must be a valid absolute URI");

        assertThatThrownBy(() -> new B2StorageProperties(VALID_BUCKET, "http://", VALID_ACCESS_KEY, VALID_SECRET_KEY, "us-east-005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Backblaze B2 endpoint must be a valid URI");
    }

    @Test
    @DisplayName("toString masks credentials")
    void shouldMaskCredentialsInToString() {
        B2StorageProperties properties = new B2StorageProperties(
                VALID_BUCKET,
                VALID_ENDPOINT,
                VALID_ACCESS_KEY,
                VALID_SECRET_KEY,
                "us-east-005"
        );

        String str = properties.toString();
        assertThat(str).doesNotContain(VALID_ACCESS_KEY);
        assertThat(str).doesNotContain(VALID_SECRET_KEY);
        assertThat(str).contains("[PROTECTED]");
        assertThat(str).contains(VALID_BUCKET);
        assertThat(str).contains(VALID_ENDPOINT);
    }
}
