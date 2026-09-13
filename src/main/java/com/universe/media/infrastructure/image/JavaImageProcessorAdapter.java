package com.universe.media.infrastructure.image;

import com.universe.media.application.exceptions.ImageMimeMismatchException;
import com.universe.media.application.exceptions.ImageProcessingException;
import com.universe.media.application.exceptions.MalformedImageException;
import com.universe.media.application.exceptions.UnsafeImageDimensionsException;
import com.universe.media.application.exceptions.UnsupportedImageFormatException;
import com.universe.media.application.ports.image.ImageProcessorPort;
import com.universe.media.application.ports.image.ProcessedImageResource;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MimeType;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Component
public class JavaImageProcessorAdapter implements ImageProcessorPort {

    public static final int MIN_SOURCE_DIMENSION = 1;
    public static final int MAX_SOURCE_DIMENSION = 8192;
    public static final long MAX_SOURCE_PIXELS = 20_000_000L;

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png"
    );

    @Override
    public ProcessedImageResource process(
            InputStream sourceStream,
            MimeType sourceMimeType,
            ImageVariantSpec spec
    ) {
        Objects.requireNonNull(sourceStream, "Source input stream cannot be null.");
        Objects.requireNonNull(sourceMimeType, "Source MIME type cannot be null.");
        Objects.requireNonNull(spec, "ImageVariantSpec cannot be null.");

        String mimeValue = sourceMimeType.value().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MIME_TYPES.contains(mimeValue)) {
            throw new UnsupportedImageFormatException("Unsupported image MIME format: " + mimeValue);
        }

        boolean isPng = "image/png".equals(mimeValue);

        ImageInputStream iis;
        try {
            iis = ImageIO.createImageInputStream(new NonClosingInputStream(sourceStream));
        } catch (IOException e) {
            throw new MalformedImageException("Failed to initialize image input stream: " + e.getMessage(), e);
        }

        if (iis == null) {
            throw new MalformedImageException("Could not create ImageInputStream from provided source stream.");
        }

        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            closeQuietly(iis);
            throw new MalformedImageException("Unrecognized or malformed image content.");
        }

        ImageReader reader = readers.next();
        int sourceWidth;
        int sourceHeight;
        BufferedImage sourceImage;

        try {
            String formatName = reader.getFormatName();
            if (formatName == null) {
                throw new MalformedImageException("Unable to determine image format name.");
            }

            String lowerFormat = formatName.toLowerCase(Locale.ROOT);
            if (isPng && !lowerFormat.contains("png")) {
                throw new ImageMimeMismatchException(
                        "Declared MIME type is " + mimeValue + " but actual format is " + formatName
                );
            }
            if (!isPng && !(lowerFormat.contains("jpeg") || lowerFormat.contains("jpg"))) {
                throw new ImageMimeMismatchException(
                        "Declared MIME type is " + mimeValue + " but actual format is " + formatName
                );
            }

            reader.setInput(iis, false, false);
            sourceWidth = reader.getWidth(0);
            sourceHeight = reader.getHeight(0);

            if (sourceWidth < MIN_SOURCE_DIMENSION || sourceHeight < MIN_SOURCE_DIMENSION) {
                throw new MalformedImageException(
                        "Image dimensions must be positive: " + sourceWidth + "x" + sourceHeight
                );
            }

            if (sourceWidth > MAX_SOURCE_DIMENSION || sourceHeight > MAX_SOURCE_DIMENSION) {
                throw new UnsafeImageDimensionsException(
                        "Image dimension (" + sourceWidth + "x" + sourceHeight + ") exceeds maximum allowed dimension (" + MAX_SOURCE_DIMENSION + "px)"
                );
            }

            long totalPixels = (long) sourceWidth * sourceHeight;
            if (totalPixels > MAX_SOURCE_PIXELS) {
                throw new UnsafeImageDimensionsException(
                        "Total image pixels (" + totalPixels + ") exceeds maximum allowed limit (" + MAX_SOURCE_PIXELS + " pixels)"
                );
            }

            sourceImage = reader.read(0);
            if (sourceImage == null) {
                throw new MalformedImageException("Decoded image raster is null.");
            }
        } catch (ImageProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new MalformedImageException("Failed to decode image data: " + e.getMessage(), e);
        } finally {
            reader.dispose();
            closeQuietly(iis);
        }

        int targetWidth = spec.targetWidth();
        int outputWidth;
        int outputHeight;

        if (sourceWidth <= targetWidth) {
            outputWidth = sourceWidth;
            outputHeight = sourceHeight;
        } else {
            outputWidth = targetWidth;
            outputHeight = Math.max(1, (int) Math.round((double) sourceHeight * targetWidth / sourceWidth));
        }

        int targetImageType = isPng ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage resizedImage = new BufferedImage(outputWidth, outputHeight, targetImageType);
        Graphics2D g2d = resizedImage.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.drawImage(sourceImage, 0, 0, outputWidth, outputHeight, null);
        } finally {
            g2d.dispose();
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("media_variant_", isPng ? ".png" : ".jpg");
            String formatName = isPng ? "png" : "jpeg";
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
                boolean written = ImageIO.write(resizedImage, formatName, os);
                if (!written) {
                    throw new ImageProcessingException("Failed to encode image in format: " + formatName);
                }
            }

            long sizeBytes = Files.size(tempFile);
            MimeType outputMimeType = isPng ? MimeType.of("image/png") : MimeType.of("image/jpeg");
            return new TempFileProcessedImageResource(tempFile, outputMimeType, sizeBytes, outputWidth, outputHeight);
        } catch (Exception e) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception cleanupEx) {
                    e.addSuppressed(cleanupEx);
                }
            }
            if (e instanceof ImageProcessingException ipe) {
                throw ipe;
            }
            throw new ImageProcessingException("Error saving processed image: " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(ImageInputStream iis) {
        try {
            iis.close();
        } catch (IOException ignored) {
        }
    }

    private static final class NonClosingInputStream extends FilterInputStream {

        private NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // No-op: do not close caller-owned source stream
        }
    }
}
