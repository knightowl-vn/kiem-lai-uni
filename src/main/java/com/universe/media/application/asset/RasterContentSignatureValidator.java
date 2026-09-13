package com.universe.media.application.asset;

import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.domain.MimeType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;
import java.util.Objects;

@Component
public class RasterContentSignatureValidator {

    public static final String MIME_JPEG = "image/jpeg";
    public static final String MIME_PNG = "image/png";
    public static final String MIME_WEBP = "image/webp";

    public static final int JPEG_PREFIX_LENGTH = 3;
    public static final int PNG_PREFIX_LENGTH = 8;
    public static final int WEBP_PREFIX_LENGTH = 12;

    public InputStream validateAndPrepareStream(
            InputStream content,
            long sizeBytes,
            MimeType mimeType
    ) {
        Objects.requireNonNull(content, "Content InputStream cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        String mimeValue = mimeType.value().toLowerCase(Locale.ROOT);
        int requiredPrefixLength = getRequiredPrefixLength(mimeValue);

        if (requiredPrefixLength <= 0) {
            return content;
        }

        if (sizeBytes < requiredPrefixLength) {
            throw new UploadContentMimeMismatchException(
                    "Content size (" + sizeBytes + " bytes) is too small for declared MIME type '" + mimeValue
                            + "' (requires at least " + requiredPrefixLength + " bytes)."
            );
        }

        PushbackInputStream pushbackStream = new NonClosingPushbackInputStream(content, requiredPrefixLength);
        byte[] prefix = new byte[requiredPrefixLength];
        int totalRead = 0;

        try {
            while (totalRead < requiredPrefixLength) {
                int read = pushbackStream.read(prefix, totalRead, requiredPrefixLength - totalRead);
                if (read == -1) {
                    break;
                }
                totalRead += read;
            }
        } catch (IOException e) {
            throw new UploadContentMimeMismatchException(
                    "Failed to read binary header prefix for '" + mimeValue + "': " + e.getMessage(),
                    e
            );
        }

        if (totalRead < requiredPrefixLength) {
            throw new UploadContentMimeMismatchException(
                    "Content stream ended prematurely (" + totalRead + " bytes read) for declared MIME type '" + mimeValue
                            + "' (requires at least " + requiredPrefixLength + " bytes)."
            );
        }

        if (!matchesSignature(mimeValue, prefix)) {
            throw new UploadContentMimeMismatchException(
                    "Content binary signature does not match declared raster MIME type '" + mimeValue + "'."
            );
        }

        try {
            pushbackStream.unread(prefix, 0, totalRead);
        } catch (IOException e) {
            throw new UploadContentMimeMismatchException(
                    "Failed to unread binary header prefix for '" + mimeValue + "': " + e.getMessage(),
                    e
            );
        }

        return pushbackStream;
    }

    private int getRequiredPrefixLength(String mimeValue) {
        if (MIME_JPEG.equals(mimeValue)) {
            return JPEG_PREFIX_LENGTH;
        } else if (MIME_PNG.equals(mimeValue)) {
            return PNG_PREFIX_LENGTH;
        } else if (MIME_WEBP.equals(mimeValue)) {
            return WEBP_PREFIX_LENGTH;
        }
        return 0;
    }

    private boolean matchesSignature(String mimeValue, byte[] prefix) {
        return switch (mimeValue) {
            case MIME_JPEG -> matchesJpeg(prefix);
            case MIME_PNG -> matchesPng(prefix);
            case MIME_WEBP -> matchesWebp(prefix);
            default -> false;
        };
    }

    private boolean matchesJpeg(byte[] prefix) {
        return (prefix[0] & 0xFF) == 0xFF
                && (prefix[1] & 0xFF) == 0xD8
                && (prefix[2] & 0xFF) == 0xFF;
    }

    private boolean matchesPng(byte[] prefix) {
        return (prefix[0] & 0xFF) == 0x89
                && (prefix[1] & 0xFF) == 0x50
                && (prefix[2] & 0xFF) == 0x4E
                && (prefix[3] & 0xFF) == 0x47
                && (prefix[4] & 0xFF) == 0x0D
                && (prefix[5] & 0xFF) == 0x0A
                && (prefix[6] & 0xFF) == 0x1A
                && (prefix[7] & 0xFF) == 0x0A;
    }

    private boolean matchesWebp(byte[] prefix) {
        return (prefix[0] & 0xFF) == 0x52   // 'R'
                && (prefix[1] & 0xFF) == 0x49 // 'I'
                && (prefix[2] & 0xFF) == 0x46 // 'F'
                && (prefix[3] & 0xFF) == 0x46 // 'F'
                && (prefix[8] & 0xFF) == 0x57 // 'W'
                && (prefix[9] & 0xFF) == 0x45 // 'E'
                && (prefix[10] & 0xFF) == 0x42 // 'B'
                && (prefix[11] & 0xFF) == 0x50; // 'P'
    }

    private static class NonClosingPushbackInputStream extends PushbackInputStream {

        NonClosingPushbackInputStream(InputStream in, int size) {
            super(in, size);
        }

        @Override
        public void close() throws IOException {
            // No-op to preserve caller InputStream ownership
        }
    }
}
