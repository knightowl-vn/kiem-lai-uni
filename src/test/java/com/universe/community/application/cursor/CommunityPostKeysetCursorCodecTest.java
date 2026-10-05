package com.universe.community.application.cursor;

import com.universe.community.domain.exception.CommunityPostValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityPostKeysetCursorCodec Unit Tests")
class CommunityPostKeysetCursorCodecTest {

    private CommunityPostKeysetCursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new CommunityPostKeysetCursorCodec();
    }

    @Test
    @DisplayName("encode() and decode() should round-trip correctly")
    void shouldRoundTripEncodeAndDecode() {
        Instant now = Instant.parse("2026-09-30T12:34:56.789123Z");
        UUID postId = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d");
        CommunityPostKeysetCursor cursor = new CommunityPostKeysetCursor(now, postId);

        String encoded = codec.encode(cursor);
        assertThat(encoded).isNotBlank();

        CommunityPostKeysetCursor decoded = codec.decode(encoded);
        assertThat(decoded).isNotNull();
        assertThat(decoded.publishedAt()).isEqualTo(now);
        assertThat(decoded.postId()).isEqualTo(postId);
    }

    @Test
    @DisplayName("decode() with null, empty, or blank string should return null")
    void shouldReturnNullForEmptyCursorString() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("")).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    @DisplayName("encode() with null cursor should return null")
    void shouldReturnNullWhenEncodingNullCursor() {
        assertThat(codec.encode(null)).isNull();
    }

    @Test
    @DisplayName("decode() with non-Base64 malformed string should throw CommunityPostValidationException")
    void shouldThrowOnNonBase64Cursor() {
        assertThatThrownBy(() -> codec.decode("not-valid-base64!@#$%^&*"))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Invalid cursor format.");
    }

    @Test
    @DisplayName("decode() without delimiter should throw CommunityPostValidationException")
    void shouldThrowWhenDelimiterIsMissing() {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("2026-09-30T12:00:00Z".getBytes());
        assertThatThrownBy(() -> codec.decode(encoded))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Invalid cursor format.");
    }

    @Test
    @DisplayName("decode() with invalid ISO Instant should throw CommunityPostValidationException")
    void shouldThrowOnInvalidInstant() {
        String raw = "invalid-date|" + UUID.randomUUID();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
        assertThatThrownBy(() -> codec.decode(encoded))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Invalid cursor format.");
    }

    @Test
    @DisplayName("decode() with invalid UUID should throw CommunityPostValidationException")
    void shouldThrowOnInvalidUuid() {
        String raw = Instant.now() + "|invalid-uuid";
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
        assertThatThrownBy(() -> codec.decode(encoded))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Invalid cursor format.");
    }
}
