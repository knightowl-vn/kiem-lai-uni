package com.universe.media.application.asset;

import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MimeType;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Validates VIDEO media upload constraints according to the approved product contract (MS-08C2).
 * <p>
 * Constraints:
 * <ul>
 *     <li>MediaType must be {@link MediaType#VIDEO}</li>
 *     <li>Payload size must be strictly positive: {@code sizeBytes > 0}</li>
 *     <li>Maximum payload size is 4 GiB (4,294,967,296 bytes)</li>
 *     <li>MIME content type must be canonically {@code video/mp4}</li>
 * </ul>
 */
@Component
public class VideoUploadValidator {

    public static final long MAX_VIDEO_SIZE_BYTES = 4_294_967_296L; // 4 GiB = 4 * 1024 * 1024 * 1024
    public static final String CANONICAL_MIME_TYPE = "video/mp4";

    /**
     * Validates that the given upload parameters strictly satisfy the approved VIDEO policy.
     *
     * @param mediaType the media type category
     * @param sizeBytes declared payload size in bytes
     * @param mimeType declared MIME type
     * @throws IllegalArgumentException if sizeBytes is non-positive or exceeds 4 GiB
     * @throws UploadContentMimeMismatchException if MIME type is not video/mp4
     */
    public void validate(MediaType mediaType, long sizeBytes, MimeType mimeType) {
        Objects.requireNonNull(mediaType, "MediaType cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        if (mediaType != MediaType.VIDEO) {
            throw new IllegalArgumentException("VideoUploadValidator can only validate MediaType.VIDEO, got: " + mediaType);
        }

        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("VIDEO payload size must be positive: " + sizeBytes);
        }

        if (sizeBytes > MAX_VIDEO_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "VIDEO payload size (" + sizeBytes + " bytes) exceeds maximum allowed size of "
                            + MAX_VIDEO_SIZE_BYTES + " bytes (4 GiB)."
            );
        }

        if (!CANONICAL_MIME_TYPE.equalsIgnoreCase(mimeType.value())) {
            throw new UploadContentMimeMismatchException(
                    "Unsupported MIME type '" + mimeType.value() + "' for VIDEO. Only '"
                            + CANONICAL_MIME_TYPE + "' is accepted in V1."
            );
        }
    }
}
