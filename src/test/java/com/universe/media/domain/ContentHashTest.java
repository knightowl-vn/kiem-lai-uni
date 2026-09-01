package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentHashTest {

    @Test
    @DisplayName("valid 64-character SHA-256 hex string is accepted and lowercased")
    void shouldAcceptValidSha256Hash() {
        String hex = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855";
        ContentHash hash = ContentHash.of(hex);

        assertThat(hash.value()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(hash.toString()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("rejects null or blank content hash")
    void shouldRejectNullOrBlank() {
        assertThatThrownBy(() -> ContentHash.of(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ContentHash.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be exactly 64 lowercase hexadecimal characters");
    }

    @Test
    @DisplayName("rejects hash with invalid length (< 64 or > 64)")
    void shouldRejectInvalidLength() {
        assertThatThrownBy(() -> ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be exactly 64 lowercase hexadecimal characters");

        assertThatThrownBy(() -> ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855aa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be exactly 64 lowercase hexadecimal characters");
    }

    @Test
    @DisplayName("rejects hash with non-hex characters")
    void shouldRejectNonHexCharacters() {
        String invalidHex = "z3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertThatThrownBy(() -> ContentHash.of(invalidHex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be exactly 64 lowercase hexadecimal characters");
    }
}
