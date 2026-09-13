package com.universe.media.application.asset;

import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.domain.MimeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RasterContentSignatureValidatorTest {

    private RasterContentSignatureValidator validator;

    private static final byte[] VALID_JPEG_HEADER = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0
    };

    private static final byte[] VALID_PNG_HEADER = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private static final byte[] VALID_WEBP_HEADER = new byte[]{
            'R', 'I', 'F', 'F', 0x20, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'
    };

    @BeforeEach
    void setUp() {
        validator = new RasterContentSignatureValidator();
    }

    @Nested
    @DisplayName("Valid Raster Content Signature Tests")
    class ValidSignatureTests {

        @Test
        @DisplayName("valid JPEG content passes validation and full byte stream is preserved for replay")
        void shouldAcceptValidJpeg() throws IOException {
            byte[] payload = combine(VALID_JPEG_HEADER, "JPEG image payload body".getBytes(StandardCharsets.UTF_8));
            ByteArrayInputStream input = new ByteArrayInputStream(payload);

            InputStream prepared = validator.validateAndPrepareStream(input, payload.length, MimeType.of("image/jpeg"));

            assertThat(prepared).isNotNull();
            byte[] readBytes = prepared.readAllBytes();
            assertThat(readBytes).isEqualTo(payload);
        }

        @Test
        @DisplayName("valid PNG content passes validation and full byte stream is preserved for replay")
        void shouldAcceptValidPng() throws IOException {
            byte[] payload = combine(VALID_PNG_HEADER, "PNG image payload body".getBytes(StandardCharsets.UTF_8));
            ByteArrayInputStream input = new ByteArrayInputStream(payload);

            InputStream prepared = validator.validateAndPrepareStream(input, payload.length, MimeType.of("image/png"));

            assertThat(prepared).isNotNull();
            byte[] readBytes = prepared.readAllBytes();
            assertThat(readBytes).isEqualTo(payload);
        }

        @Test
        @DisplayName("valid WebP content passes validation and full byte stream is preserved for replay")
        void shouldAcceptValidWebP() throws IOException {
            byte[] payload = combine(VALID_WEBP_HEADER, "WebP image payload body".getBytes(StandardCharsets.UTF_8));
            ByteArrayInputStream input = new ByteArrayInputStream(payload);

            InputStream prepared = validator.validateAndPrepareStream(input, payload.length, MimeType.of("image/webp"));

            assertThat(prepared).isNotNull();
            byte[] readBytes = prepared.readAllBytes();
            assertThat(readBytes).isEqualTo(payload);
        }
    }

    @Nested
    @DisplayName("Non-Raster MIME Type Bypass Tests")
    class NonRasterBypassTests {

        @ParameterizedTest(name = "bypasses signature check for non-raster MIME: {0}")
        @ValueSource(strings = {"audio/mpeg", "video/mp4", "application/pdf", "text/plain"})
        void shouldBypassValidationForNonRasterMimeTypes(String mimeTypeStr) throws IOException {
            byte[] arbitraryData = "Arbitrary arbitrary non-image binary stream data".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream input = new ByteArrayInputStream(arbitraryData);

            InputStream prepared = validator.validateAndPrepareStream(input, arbitraryData.length, MimeType.of(mimeTypeStr));

            assertThat(prepared).isSameAs(input);
            assertThat(prepared.readAllBytes()).isEqualTo(arbitraryData);
        }
    }

    @Nested
    @DisplayName("Signature Mismatch & Hostile Content Rejection Tests")
    class SignatureMismatchTests {

        @Test
        @DisplayName("rejects JPEG content declared as image/png")
        void shouldRejectJpegDeclaredAsPng() {
            byte[] payload = combine(VALID_JPEG_HEADER, "mismatched".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    new ByteArrayInputStream(payload),
                    payload.length,
                    MimeType.of("image/png")
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("Content binary signature does not match declared raster MIME type 'image/png'");
        }

        @Test
        @DisplayName("rejects PNG content declared as image/webp")
        void shouldRejectPngDeclaredAsWebP() {
            byte[] payload = combine(VALID_PNG_HEADER, "mismatched".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    new ByteArrayInputStream(payload),
                    payload.length,
                    MimeType.of("image/webp")
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("Content binary signature does not match declared raster MIME type 'image/webp'");
        }

        @Test
        @DisplayName("rejects WebP content declared as image/jpeg")
        void shouldRejectWebPDeclaredAsJpeg() {
            byte[] payload = combine(VALID_WEBP_HEADER, "mismatched".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    new ByteArrayInputStream(payload),
                    payload.length,
                    MimeType.of("image/jpeg")
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("Content binary signature does not match declared raster MIME type 'image/jpeg'");
        }

        @ParameterizedTest(name = "rejects HTML/JS/script declared as raster MIME: {0}")
        @ValueSource(strings = {"image/jpeg", "image/png", "image/webp"})
        void shouldRejectHtmlOrScriptDeclaredAsRaster(String mimeTypeStr) {
            byte[] hostilePayload = "<html><script>alert('xss')</script></html>".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    new ByteArrayInputStream(hostilePayload),
                    hostilePayload.length,
                    MimeType.of(mimeTypeStr)
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("Content binary signature does not match declared raster MIME type");
        }

        @ParameterizedTest(name = "rejects executable binary header declared as raster MIME: {0}")
        @ValueSource(strings = {"image/jpeg", "image/png", "image/webp"})
        void shouldRejectExecutableBinaryDeclaredAsRaster(String mimeTypeStr) {
            byte[] exePayload = new byte[]{'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x05};

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    new ByteArrayInputStream(exePayload),
                    exePayload.length,
                    MimeType.of(mimeTypeStr)
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("Content binary signature does not match declared raster MIME type");
        }
    }

    @Nested
    @DisplayName("Short Content & Truncated Stream Rejection Tests")
    class ShortContentTests {

        @Test
        @DisplayName("rejects JPEG content when declared size is less than 3 bytes")
        void shouldRejectTooShortJpegBySize() {
            byte[] tooShort = new byte[]{(byte) 0xFF, (byte) 0xD8};

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    new ByteArrayInputStream(tooShort),
                    tooShort.length,
                    MimeType.of("image/jpeg")
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("too small for declared MIME type 'image/jpeg' (requires at least 3 bytes)");
        }

        @Test
        @DisplayName("rejects PNG content when declared size is less than 8 bytes")
        void shouldRejectTooShortPngBySize() {
            byte[] tooShort = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A};

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    new ByteArrayInputStream(tooShort),
                    tooShort.length,
                    MimeType.of("image/png")
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("too small for declared MIME type 'image/png' (requires at least 8 bytes)");
        }

        @Test
        @DisplayName("rejects WebP content when declared size is less than 12 bytes")
        void shouldRejectTooShortWebPBySize() {
            byte[] tooShort = "RIFF1234WEB".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    new ByteArrayInputStream(tooShort),
                    tooShort.length,
                    MimeType.of("image/webp")
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("too small for declared MIME type 'image/webp' (requires at least 12 bytes)");
        }

        @Test
        @DisplayName("rejects stream when EOF occurs before reading declared prefix bytes")
        void shouldRejectWhenStreamEndsPrematurely() {
            byte[] truncated = new byte[]{'R', 'I', 'F'}; // 3 bytes, but declared size 100
            ByteArrayInputStream truncatedStream = new ByteArrayInputStream(truncated);

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    truncatedStream,
                    100L,
                    MimeType.of("image/webp")
            )).isInstanceOf(UploadContentMimeMismatchException.class)
                    .hasMessageContaining("Content stream ended prematurely (3 bytes read) for declared MIME type 'image/webp'");
        }
    }

    @Nested
    @DisplayName("Caller Stream Ownership Tests")
    class CallerStreamOwnershipTests {

        @Test
        @DisplayName("closing the prepared wrapper stream does NOT close caller's underlying stream")
        void shouldNotCloseCallerUnderlyingStream() throws IOException {
            byte[] payload = combine(VALID_PNG_HEADER, "test payload".getBytes(StandardCharsets.UTF_8));
            AtomicBoolean callerStreamClosed = new AtomicBoolean(false);

            InputStream callerStream = new FilterInputStream(new ByteArrayInputStream(payload)) {
                @Override
                public void close() throws IOException {
                    callerStreamClosed.set(true);
                    super.close();
                }
            };

            InputStream prepared = validator.validateAndPrepareStream(callerStream, payload.length, MimeType.of("image/png"));
            prepared.close();

            assertThat(callerStreamClosed.get()).isFalse();
        }

        @Test
        @DisplayName("failing validation does NOT close caller's underlying stream")
        void shouldNotCloseCallerStreamOnValidationFailure() {
            byte[] invalidPayload = "invalid data".getBytes(StandardCharsets.UTF_8);
            AtomicBoolean callerStreamClosed = new AtomicBoolean(false);

            InputStream callerStream = new FilterInputStream(new ByteArrayInputStream(invalidPayload)) {
                @Override
                public void close() throws IOException {
                    callerStreamClosed.set(true);
                    super.close();
                }
            };

            assertThatThrownBy(() -> validator.validateAndPrepareStream(
                    callerStream,
                    invalidPayload.length,
                    MimeType.of("image/png")
            )).isInstanceOf(UploadContentMimeMismatchException.class);

            assertThat(callerStreamClosed.get()).isFalse();
        }
    }

    private static byte[] combine(byte[] header, byte[] body) {
        byte[] result = new byte[header.length + body.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(body, 0, result, header.length, body.length);
        return result;
    }
}
