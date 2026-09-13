package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MimeTypeTest {

    @Test
    @DisplayName("valid MIME types are accepted, trimmed, and lowercased")
    void shouldAcceptValidMimeTypes() {
        MimeType jpeg = MimeType.of("  IMAGE/JPEG ");
        assertThat(jpeg.value()).isEqualTo("image/jpeg");
        assertThat(jpeg.toString()).isEqualTo("image/jpeg");

        MimeType webp = MimeType.of("image/webp");
        assertThat(webp.value()).isEqualTo("image/webp");

        MimeType pdf = MimeType.of("application/pdf");
        assertThat(pdf.value()).isEqualTo("application/pdf");

        MimeType audio = MimeType.of("audio/mpeg");
        assertThat(audio.value()).isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("rejects null or blank MIME type")
    void shouldRejectNullOrBlank() {
        assertThatThrownBy(() -> MimeType.of(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> MimeType.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MimeType cannot be blank");
    }

    @Test
    @DisplayName("rejects MIME type exceeding max length (100)")
    void shouldRejectExceedingMaxLength() {
        String tooLong = "image/" + "a".repeat(95);
        assertThatThrownBy(() -> MimeType.of(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 100 characters");
    }

    @Test
    @DisplayName("rejects invalid MIME formats without slash")
    void shouldRejectInvalidMimeFormat() {
        assertThatThrownBy(() -> MimeType.of("invalid_no_slash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MimeType is not in a valid format");

        assertThatThrownBy(() -> MimeType.of("image/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MimeType is not in a valid format");

        assertThatThrownBy(() -> MimeType.of("/jpeg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MimeType is not in a valid format");
    }
}
