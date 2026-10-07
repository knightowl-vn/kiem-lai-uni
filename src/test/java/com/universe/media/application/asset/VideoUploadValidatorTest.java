package com.universe.media.application.asset;

import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MimeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoUploadValidatorTest {

    private VideoUploadValidator validator;

    private static final long EXACT_4_GIB = 4_294_967_296L;
    private static final long EXCEEDING_4_GIB = 4_294_967_297L;

    @BeforeEach
    void setUp() {
        validator = new VideoUploadValidator();
    }

    @Nested
    @DisplayName("VIDEO Size Boundary Validation")
    class SizeBoundaryTests {

        @Test
        @DisplayName("1 byte payload is accepted as non-empty lower boundary")
        void shouldAcceptOneByteLowerBoundary() {
            assertThatCode(() -> validator.validate(
                    MediaType.VIDEO,
                    1L,
                    MimeType.of("video/mp4")
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("exact 4 GiB (4,294,967,296 bytes) payload is accepted as upper boundary")
        void shouldAcceptExact4GiBUpperBoundary() {
            assertThatCode(() -> validator.validate(
                    MediaType.VIDEO,
                    EXACT_4_GIB,
                    MimeType.of("video/mp4")
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("4 GiB + 1 byte (4,294,967,297 bytes) payload is strictly rejected")
        void shouldRejectExceeding4GiBByOneByte() {
            assertThatThrownBy(() -> validator.validate(
                    MediaType.VIDEO,
                    EXCEEDING_4_GIB,
                    MimeType.of("video/mp4")
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum allowed size of 4294967296 bytes (4 GiB)");
        }

        @ParameterizedTest(name = "rejects non-positive size: {0}")
        @ValueSource(longs = {0L, -1L, -100L})
        void shouldRejectNonPositiveSizes(long invalidSize) {
            assertThatThrownBy(() -> validator.validate(
                    MediaType.VIDEO,
                    invalidSize,
                    MimeType.of("video/mp4")
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }
    }

    @Nested
    @DisplayName("VIDEO MIME Type Validation")
    class MimeValidationTests {

        @ParameterizedTest(name = "canonical video/mp4 accepted: {0}")
        @ValueSource(strings = {"video/mp4", "VIDEO/MP4", "Video/Mp4"})
        void shouldAcceptCanonicalMp4Mime(String mime) {
            assertThatCode(() -> validator.validate(
                    MediaType.VIDEO,
                    1024L,
                    MimeType.of(mime)
            )).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "non-mp4 MIME rejected: {0}")
        @ValueSource(strings = {
                "video/webm",
                "video/quicktime",
                "video/x-matroska",
                "video/avi",
                "image/png",
                "application/octet-stream"
        })
        void shouldRejectNonMp4Mimes(String invalidMime) {
            assertThatThrownBy(() -> validator.validate(
                    MediaType.VIDEO,
                    1024L,
                    MimeType.of(invalidMime)
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("Unsupported MIME type '" + invalidMime + "' for VIDEO. Only 'video/mp4' is accepted in V1.");
        }
    }

    @Nested
    @DisplayName("MediaType Category Validation")
    class CategoryValidationTests {

        @ParameterizedTest(name = "rejects non-VIDEO media type: {0}")
        @ValueSource(strings = {"IMAGE", "AUDIO", "DOCUMENT"})
        void shouldRejectNonVideoMediaType(String mediaTypeName) {
            MediaType type = MediaType.valueOf(mediaTypeName);
            assertThatThrownBy(() -> validator.validate(
                    type,
                    1024L,
                    MimeType.of("video/mp4")
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("VideoUploadValidator can only validate MediaType.VIDEO");
        }
    }
}
