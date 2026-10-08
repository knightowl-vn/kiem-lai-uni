package com.universe.media.application.asset;

import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MimeType;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Validates VIDEO media upload constraints according to the approved product contract.
 * <p>
 * Supports dual upload ceilings:
 * <ul>
 *     <li>Single-stream direct upload: 4 GiB (4,294,967,296 bytes)</li>
 *     <li>Multipart presigned upload: 50 GiB (53,687,091,200 bytes)</li>
 * </ul>
 * <p>
 * Constraints:
 * <ul>
 *     <li>MediaType must be {@link MediaType#VIDEO}</li>
 *     <li>Payload size must be strictly positive: {@code sizeBytes > 0}</li>
 *     <li>MIME content type must be canonically {@code video/mp4}</li>
 *     <li>For multipart uploads, part size must be between 5 MiB and 5 GiB, resulting in <= 10,000 parts</li>
 * </ul>
 */
@Component
public class VideoUploadValidator {

    public static final long MAX_VIDEO_SIZE_BYTES = 4_294_967_296L; // 4 GiB = 4 * 1024 * 1024 * 1024
    public static final long MAX_SINGLE_STREAM_VIDEO_SIZE_BYTES = 4_294_967_296L; // 4 GiB
    public static final long MAX_MULTIPART_VIDEO_SIZE_BYTES = 53_687_091_200L; // 50 GiB = 50 * 1024 * 1024 * 1024
    public static final long DEFAULT_PART_SIZE_BYTES = 25 * 1024 * 1024L; // 25 MiB = 26,214,400 bytes
    public static final long MIN_PART_SIZE_BYTES = 5 * 1024 * 1024L; // 5 MiB = 5,242,880 bytes
    public static final long MAX_PART_SIZE_BYTES = 5_368_709_120L; // 5 GiB = 5 * 1024 * 1024 * 1024
    public static final int MAX_PART_COUNT = 10_000;

    public static final String CANONICAL_MIME_TYPE = "video/mp4";

    /**
     * Validates that the given upload parameters strictly satisfy the single-stream VIDEO policy.
     * Maintained for backward compatibility with existing single-stream use cases.
     *
     * @param mediaType the media type category
     * @param sizeBytes declared payload size in bytes
     * @param mimeType declared MIME type
     * @throws IllegalArgumentException if sizeBytes is non-positive or exceeds 4 GiB
     * @throws UploadContentMimeMismatchException if MIME type is not video/mp4
     */
    public void validate(MediaType mediaType, long sizeBytes, MimeType mimeType) {
        validateSingleStreamUpload(mediaType, sizeBytes, mimeType);
    }

    /**
     * Validates single-stream VIDEO upload parameters (4 GiB ceiling).
     *
     * @param mediaType the media type category
     * @param sizeBytes declared payload size in bytes
     * @param mimeType declared MIME type
     * @throws IllegalArgumentException if sizeBytes is non-positive or exceeds 4 GiB
     * @throws UploadContentMimeMismatchException if MIME type is not video/mp4
     */
    public void validateSingleStreamUpload(MediaType mediaType, long sizeBytes, MimeType mimeType) {
        Objects.requireNonNull(mediaType, "MediaType cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        if (mediaType != MediaType.VIDEO) {
            throw new IllegalArgumentException("VideoUploadValidator can only validate MediaType.VIDEO, got: " + mediaType);
        }

        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("VIDEO payload size must be positive: " + sizeBytes);
        }

        if (sizeBytes > MAX_SINGLE_STREAM_VIDEO_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "VIDEO payload size (" + sizeBytes + " bytes) exceeds maximum allowed size of "
                            + MAX_SINGLE_STREAM_VIDEO_SIZE_BYTES + " bytes (4 GiB)."
            );
        }

        validateMimeType(mimeType);
    }

    /**
     * Validates multipart session VIDEO upload parameters (50 GiB ceiling) using default 25 MiB part size.
     *
     * @param mediaType the media type category
     * @param sizeBytes declared payload size in bytes
     * @param mimeType declared MIME type
     * @throws IllegalArgumentException if sizeBytes is non-positive or exceeds 50 GiB
     * @throws UploadContentMimeMismatchException if MIME type is not video/mp4
     */
    public void validateMultipartSessionUpload(MediaType mediaType, long sizeBytes, MimeType mimeType) {
        validateMultipartSessionUpload(mediaType, sizeBytes, mimeType, DEFAULT_PART_SIZE_BYTES);
    }

    /**
     * Validates multipart session VIDEO upload parameters (50 GiB ceiling) with explicit part size.
     *
     * @param mediaType the media type category
     * @param sizeBytes declared payload size in bytes
     * @param mimeType declared MIME type
     * @param partSizeBytes part size in bytes (minimum 5 MiB, maximum 5 GiB)
     * @throws IllegalArgumentException if parameters violate multipart rules
     * @throws UploadContentMimeMismatchException if MIME type is not video/mp4
     */
    public void validateMultipartSessionUpload(MediaType mediaType, long sizeBytes, MimeType mimeType, long partSizeBytes) {
        Objects.requireNonNull(mediaType, "MediaType cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        if (mediaType != MediaType.VIDEO) {
            throw new IllegalArgumentException("VideoUploadValidator can only validate MediaType.VIDEO, got: " + mediaType);
        }

        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("VIDEO payload size must be positive: " + sizeBytes);
        }

        if (sizeBytes > MAX_MULTIPART_VIDEO_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Multipart VIDEO payload size (" + sizeBytes + " bytes) exceeds maximum allowed size of "
                            + MAX_MULTIPART_VIDEO_SIZE_BYTES + " bytes (50 GiB)."
            );
        }

        validateMimeType(mimeType);

        if (partSizeBytes < MIN_PART_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Multipart part size (" + partSizeBytes + " bytes) cannot be less than minimum allowed "
                            + MIN_PART_SIZE_BYTES + " bytes (5 MiB)."
            );
        }

        if (partSizeBytes > MAX_PART_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Multipart part size (" + partSizeBytes + " bytes) cannot exceed maximum allowed "
                            + MAX_PART_SIZE_BYTES + " bytes (5 GiB)."
            );
        }

        long partCount = (sizeBytes + partSizeBytes - 1) / partSizeBytes;
        if (partCount > MAX_PART_COUNT) {
            throw new IllegalArgumentException(
                    "Multipart part count (" + partCount + ") exceeds provider maximum limit of "
                            + MAX_PART_COUNT + " parts."
            );
        }
    }

    private void validateMimeType(MimeType mimeType) {
        if (!CANONICAL_MIME_TYPE.equalsIgnoreCase(mimeType.value())) {
            throw new UploadContentMimeMismatchException(
                    "Unsupported MIME type '" + mimeType.value() + "' for VIDEO. Only '"
                            + CANONICAL_MIME_TYPE + "' is accepted in V1."
            );
        }
    }
}
