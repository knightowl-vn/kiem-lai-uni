package com.universe.media.infrastructure.image;

import com.universe.media.application.exceptions.ImageMimeMismatchException;
import com.universe.media.application.exceptions.ImageProcessingException;
import com.universe.media.application.exceptions.MalformedImageException;
import com.universe.media.application.exceptions.UnsafeImageDimensionsException;
import com.universe.media.application.exceptions.UnsupportedImageFormatException;
import com.universe.media.application.ports.image.ProcessedImageResource;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MimeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaImageProcessorAdapterTest {

    private JavaImageProcessorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JavaImageProcessorAdapter();
    }

    private byte[] createJpeg(int width, int height, Color color) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", baos);
        return baos.toByteArray();
    }

    private byte[] createPng(int width, int height, Color color, boolean hasTransparency) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        if (hasTransparency) {
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, width / 2, height);
            g.setColor(color);
            g.fillRect(width / 2, 0, width - width / 2, height);
        } else {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("JPEG proportional resize preserves aspect ratio and outputs valid JPEG")
    void shouldResizeJpegProportionallyAndPreserveAspectRatio() throws IOException {
        byte[] jpegBytes = createJpeg(600, 400, Color.BLUE);
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        try (ProcessedImageResource resource = adapter.process(
                new ByteArrayInputStream(jpegBytes),
                MimeType.of("image/jpeg"),
                spec
        )) {
            assertThat(resource.mimeType().value()).isEqualTo("image/jpeg");
            assertThat(resource.width()).isEqualTo(300);
            assertThat(resource.height()).isEqualTo(200);
            assertThat(resource.sizeBytes()).isGreaterThan(0);

            try (InputStream is = resource.openStream()) {
                BufferedImage decoded = ImageIO.read(is);
                assertThat(decoded).isNotNull();
                assertThat(decoded.getWidth()).isEqualTo(300);
                assertThat(decoded.getHeight()).isEqualTo(200);
            }
        }
    }

    @Test
    @DisplayName("PNG proportional resize preserves aspect ratio and transparency")
    void shouldResizePngProportionallyAndPreserveTransparency() throws IOException {
        byte[] pngBytes = createPng(400, 400, Color.RED, true);
        ImageVariantSpec spec = ImageVariantSpec.of(200);

        try (ProcessedImageResource resource = adapter.process(
                new ByteArrayInputStream(pngBytes),
                MimeType.of("image/png"),
                spec
        )) {
            assertThat(resource.mimeType().value()).isEqualTo("image/png");
            assertThat(resource.width()).isEqualTo(200);
            assertThat(resource.height()).isEqualTo(200);
            assertThat(resource.sizeBytes()).isGreaterThan(0);

            try (InputStream is = resource.openStream()) {
                BufferedImage decoded = ImageIO.read(is);
                assertThat(decoded).isNotNull();
                assertThat(decoded.getWidth()).isEqualTo(200);
                assertThat(decoded.getHeight()).isEqualTo(200);

                // Transparent left side: alpha == 0
                int transparentPixelAlpha = (decoded.getRGB(10, 10) >>> 24) & 0xFF;
                assertThat(transparentPixelAlpha).isEqualTo(0);

                // Opaque right side: alpha == 255
                int opaquePixelAlpha = (decoded.getRGB(190, 190) >>> 24) & 0xFF;
                assertThat(opaquePixelAlpha).isEqualTo(255);
            }
        }
    }

    @Test
    @DisplayName("never upscales when source is smaller than targetWidth")
    void shouldNeverUpscaleWhenSourceIsSmallerThanTargetWidth() throws IOException {
        byte[] jpegBytes = createJpeg(150, 100, Color.GREEN);
        ImageVariantSpec spec = ImageVariantSpec.of(800);

        try (ProcessedImageResource resource = adapter.process(
                new ByteArrayInputStream(jpegBytes),
                MimeType.of("image/jpeg"),
                spec
        )) {
            assertThat(resource.width()).isEqualTo(150);
            assertThat(resource.height()).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("rejects WebP and unsupported MIME types with UnsupportedImageFormatException")
    void shouldRejectWebpAndUnsupportedMimeTypes() throws IOException {
        byte[] jpegBytes = createJpeg(200, 200, Color.BLACK);
        ImageVariantSpec spec = ImageVariantSpec.of(100);

        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(jpegBytes),
                MimeType.of("image/webp"),
                spec
        ))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("Unsupported image MIME format: image/webp");

        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(jpegBytes),
                MimeType.of("image/gif"),
                spec
        ))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("Unsupported image MIME format: image/gif");
    }

    @Test
    @DisplayName("rejects non-standard image/jpg MIME type as unsupported")
    void shouldRejectImageJpgAsUnsupportedMimeType() throws IOException {
        byte[] jpegBytes = createJpeg(200, 200, Color.BLACK);
        ImageVariantSpec spec = ImageVariantSpec.of(100);

        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(jpegBytes),
                MimeType.of("image/jpg"),
                spec
        ))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("Unsupported image MIME format: image/jpg");
    }

    @Test
    @DisplayName("source InputStream remains open after successful processing")
    void shouldKeepCallerOwnedSourceStreamOpenOnSuccess() throws IOException {
        byte[] jpegBytes = createJpeg(300, 200, Color.YELLOW);
        ImageVariantSpec spec = ImageVariantSpec.of(150);
        CloseTrackingInputStream trackingStream = new CloseTrackingInputStream(new ByteArrayInputStream(jpegBytes));

        try (ProcessedImageResource resource = adapter.process(
                trackingStream,
                MimeType.of("image/jpeg"),
                spec
        )) {
            assertThat(resource).isNotNull();
            assertThat(trackingStream.isClosed()).isFalse();
        }

        assertThat(trackingStream.isClosed()).isFalse();
        trackingStream.close();
        assertThat(trackingStream.isClosed()).isTrue();
    }

    @Test
    @DisplayName("source InputStream remains open after processing failure")
    void shouldKeepCallerOwnedSourceStreamOpenOnFailure() {
        byte[] garbage = new byte[]{1, 2, 3, 4, 5};
        ImageVariantSpec spec = ImageVariantSpec.of(100);
        CloseTrackingInputStream trackingStream = new CloseTrackingInputStream(new ByteArrayInputStream(garbage));

        assertThatThrownBy(() -> adapter.process(
                trackingStream,
                MimeType.of("image/jpeg"),
                spec
        ))
                .isInstanceOf(MalformedImageException.class);

        assertThat(trackingStream.isClosed()).isFalse();
    }

    @Test
    @DisplayName("rejects malformed image bytes with MalformedImageException")
    void shouldRejectMalformedImageContent() {
        byte[] garbage = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        ImageVariantSpec spec = ImageVariantSpec.of(100);

        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(garbage),
                MimeType.of("image/jpeg"),
                spec
        ))
                .isInstanceOf(MalformedImageException.class);

        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(garbage),
                MimeType.of("image/png"),
                spec
        ))
                .isInstanceOf(MalformedImageException.class);
    }

    @Test
    @DisplayName("rejects declared MIME and content format mismatch with ImageMimeMismatchException")
    void shouldRejectMimeContentMismatch() throws IOException {
        byte[] jpegBytes = createJpeg(200, 200, Color.ORANGE);
        byte[] pngBytes = createPng(200, 200, Color.CYAN, false);
        ImageVariantSpec spec = ImageVariantSpec.of(100);

        // Declaring image/png but providing JPEG bytes
        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(jpegBytes),
                MimeType.of("image/png"),
                spec
        ))
                .isInstanceOf(ImageMimeMismatchException.class)
                .hasMessageContaining("Declared MIME type is image/png");

        // Declaring image/jpeg but providing PNG bytes
        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(pngBytes),
                MimeType.of("image/jpeg"),
                spec
        ))
                .isInstanceOf(ImageMimeMismatchException.class)
                .hasMessageContaining("Declared MIME type is image/jpeg");
    }

    @Test
    @DisplayName("rejects unsafe image dimensions exceeding max dimension limit")
    void shouldRejectUnsafeImageDimensions() throws IOException {
        // 8193x1 exceeds MAX_SOURCE_DIMENSION (8192) while using minimal memory
        byte[] widePng = createPng(8193, 1, Color.GRAY, false);
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(widePng),
                MimeType.of("image/png"),
                spec
        ))
                .isInstanceOf(UnsafeImageDimensionsException.class)
                .hasMessageContaining("exceeds maximum allowed dimension (8192px)");
    }

    @Test
    @DisplayName("rejects unsafe pixel count exceeding 20 Megapixels limit")
    void shouldRejectUnsafeTotalPixels() throws IOException {
        // 8000x2501 = 20,008,000 pixels (> 20_000_000)
        byte[] largePng = createPng(8000, 2501, Color.GRAY, false);
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        assertThatThrownBy(() -> adapter.process(
                new ByteArrayInputStream(largePng),
                MimeType.of("image/png"),
                spec
        ))
                .isInstanceOf(UnsafeImageDimensionsException.class)
                .hasMessageContaining("exceeds maximum allowed limit (20000000 pixels)");
    }

    @Test
    @DisplayName("physically deletes temporary file on close and supports idempotent close")
    void shouldPhysicallyDeleteTempFileOnResourceClose() throws IOException {
        Path tempFile = Files.createTempFile("test_variant_res_", ".tmp");
        Files.writeString(tempFile, "sample processed content");
        assertThat(Files.exists(tempFile)).isTrue();

        TempFileProcessedImageResource resource = new TempFileProcessedImageResource(
                tempFile,
                MimeType.of("image/jpeg"),
                24L,
                100,
                100
        );

        // Readable before close
        try (InputStream is = resource.openStream()) {
            assertThat(is.readAllBytes()).isNotEmpty();
        }

        // Close resource
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();

        // Idempotent close
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();

        // openStream after close fails
        assertThatThrownBy(resource::openStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has already been closed");
    }

    @Test
    @DisplayName("closing with outstanding open stream does not permanently close or orphan, allowing retry after stream is closed")
    void shouldAllowCleanupRetryAfterOutstandingStreamIsClosed() throws IOException {
        Path tempFile = Files.createTempFile("test_retry_res_", ".tmp");
        Files.writeString(tempFile, "sample data");
        assertThat(Files.exists(tempFile)).isTrue();

        TempFileProcessedImageResource resource = new TempFileProcessedImageResource(
                tempFile,
                MimeType.of("image/jpeg"),
                11L,
                100,
                100
        );

        InputStream openStream = resource.openStream();

        // Attempting to close while stream is open must fail and not orphan or mark closed
        assertThatThrownBy(resource::close)
                .isInstanceOf(ImageProcessingException.class)
                .hasMessageContaining("must be closed first");

        // File still exists and resource is not marked closed
        assertThat(Files.exists(tempFile)).isTrue();

        // Now close the outstanding stream
        openStream.close();

        // Retry closing resource -> succeeds and physically removes file
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();

        // Further openStream fails
        assertThatThrownBy(resource::openStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has already been closed");
    }

    @Test
    @DisplayName("validates null arguments strictly")
    void shouldValidateNullArguments() throws IOException {
        byte[] jpegBytes = createJpeg(200, 200, Color.BLACK);
        ImageVariantSpec spec = ImageVariantSpec.of(100);

        assertThatThrownBy(() -> adapter.process(null, MimeType.of("image/jpeg"), spec))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> adapter.process(new ByteArrayInputStream(jpegBytes), null, spec))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> adapter.process(new ByteArrayInputStream(jpegBytes), MimeType.of("image/jpeg"), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static final class CloseTrackingInputStream extends FilterInputStream {

        private boolean closed = false;

        private CloseTrackingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
