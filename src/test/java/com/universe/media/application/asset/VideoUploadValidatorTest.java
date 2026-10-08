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

    private static final long EXACT_50_GIB = 53_687_091_200L;
    private static final long EXCEEDING_50_GIB = 53_687_091_201L;

    private static final long FIVE_MIB = 5 * 1024 * 1024L;
    private static final long TWENTY_FIVE_MIB = 25 * 1024 * 1024L;
    private static final long FIVE_GIB = 5 * 1024 * 1024 * 1024L;

    @BeforeEach
    void setUp() {
        validator = new VideoUploadValidator();
    }

    @Nested
    @DisplayName("Single-Stream VIDEO Size Boundary Validation")
    class SingleStreamSizeBoundaryTests {

        @Test
        @DisplayName("1 byte payload is accepted as non-empty lower boundary")
        void shouldAcceptOneByteLowerBoundary() {
            assertThatCode(() -> validator.validate(
                    MediaType.VIDEO,
                    1L,
                    MimeType.of("video/mp4")
            )).doesNotThrowAnyException();

            assertThatCode(() -> validator.validateSingleStreamUpload(
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

            assertThatCode(() -> validator.validateSingleStreamUpload(
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

            assertThatThrownBy(() -> validator.validateSingleStreamUpload(
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
    @DisplayName("Single-Stream VIDEO MIME Type Validation")
    class SingleStreamMimeValidationTests {

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
    @DisplayName("Single-Stream MediaType Category Validation")
    class SingleStreamCategoryValidationTests {

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

    @Nested
    @DisplayName("Multipart Session VIDEO Size Boundary Validation")
    class MultipartSizeBoundaryTests {

        @Test
        @DisplayName("1 byte payload is accepted for multipart upload")
        void shouldAcceptOneByteMultipartLowerBoundary() {
            assertThatCode(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    1L,
                    MimeType.of("video/mp4")
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("4 GiB + 1 byte is accepted for multipart upload (surpasses single-stream limit)")
        void shouldAcceptExceedingSingleStreamCeiling() {
            assertThatCode(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    EXCEEDING_4_GIB,
                    MimeType.of("video/mp4")
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("exact 50 GiB (53,687,091,200 bytes) is accepted as multipart upper boundary")
        void shouldAcceptExact50GiBUpperBoundary() {
            assertThatCode(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    EXACT_50_GIB,
                    MimeType.of("video/mp4")
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("50 GiB + 1 byte (53,687,091,201 bytes) is strictly rejected")
        void shouldRejectExceeding50GiBByOneByte() {
            assertThatThrownBy(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    EXCEEDING_50_GIB,
                    MimeType.of("video/mp4")
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Multipart VIDEO payload size (" + EXCEEDING_50_GIB + " bytes) exceeds maximum allowed size of 53687091200 bytes (50 GiB)");
        }

        @ParameterizedTest(name = "rejects non-positive multipart size: {0}")
        @ValueSource(longs = {0L, -1L, -100L})
        void shouldRejectNonPositiveMultipartSizes(long invalidSize) {
            assertThatThrownBy(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    invalidSize,
                    MimeType.of("video/mp4")
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }
    }

    @Nested
    @DisplayName("Multipart Session Part Size & Count Validation")
    class MultipartPartSizeTests {

        @Test
        @DisplayName("default part size 25 MiB with 50 GiB produces 2,048 parts and is accepted")
        void shouldAcceptDefaultPartSizeFor50GiB() {
            assertThatCode(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    EXACT_50_GIB,
                    MimeType.of("video/mp4"),
                    TWENTY_FIVE_MIB
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("part size below 5 MiB is strictly rejected")
        void shouldRejectPartSizeBelow5MiB() {
            long under5MiB = FIVE_MIB - 1;
            assertThatThrownBy(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    100 * 1024 * 1024L,
                    MimeType.of("video/mp4"),
                    under5MiB
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be less than minimum allowed 5242880 bytes (5 MiB)");
        }

        @Test
        @DisplayName("part size exceeding 5 GiB is strictly rejected")
        void shouldRejectPartSizeExceeding5GiB() {
            long over5GiB = FIVE_GIB + 1;
            assertThatThrownBy(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    EXACT_50_GIB,
                    MimeType.of("video/mp4"),
                    over5GiB
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed maximum allowed 5368709120 bytes (5 GiB)");
        }

        @Test
        @DisplayName("part size that forces part count > 10,000 is rejected")
        void shouldRejectPartCountExceeding10000() {
            // 50 GiB with 5 MiB parts = 53,687,091,200 / 5,242,880 = 10,240 parts > 10,000 max parts
            assertThatThrownBy(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    EXACT_50_GIB,
                    MimeType.of("video/mp4"),
                    FIVE_MIB
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds provider maximum limit of 10000 parts");
        }
    }

    @Nested
    @DisplayName("Multipart Session MIME & Category Validation")
    class MultipartMimeAndCategoryTests {

        @ParameterizedTest(name = "canonical video/mp4 accepted: {0}")
        @ValueSource(strings = {"video/mp4", "VIDEO/MP4", "Video/Mp4"})
        void shouldAcceptCanonicalMp4Mime(String mime) {
            assertThatCode(() -> validator.validateMultipartSessionUpload(
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
            assertThatThrownBy(() -> validator.validateMultipartSessionUpload(
                    MediaType.VIDEO,
                    1024L,
                    MimeType.of(invalidMime)
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("Unsupported MIME type '" + invalidMime + "' for VIDEO. Only 'video/mp4' is accepted in V1.");
        }

        @ParameterizedTest(name = "rejects non-VIDEO media type: {0}")
        @ValueSource(strings = {"IMAGE", "AUDIO", "DOCUMENT"})
        void shouldRejectNonVideoMediaType(String mediaTypeName) {
            MediaType type = MediaType.valueOf(mediaTypeName);
            assertThatThrownBy(() -> validator.validateMultipartSessionUpload(
                    type,
                    1024L,
                    MimeType.of("video/mp4")
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("VideoUploadValidator can only validate MediaType.VIDEO");
        }
    }
}
