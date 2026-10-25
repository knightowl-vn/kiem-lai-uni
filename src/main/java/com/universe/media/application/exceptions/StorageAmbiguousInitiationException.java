package com.universe.media.application.exceptions;

/**
 * Thrown when the outcome of a provider multipart initiation call is ambiguous
 * (e.g. HTTP 5xx, socket timeout, or transport interruption).
 *
 * <p>Because the remote provider may or may not have created a multipart upload,
 * local initiation claims must be preserved without speculative deletion pending reconciliation.</p>
 */
public class StorageAmbiguousInitiationException extends StorageException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "MEDIA_STORAGE_INITIATION_AMBIGUOUS";

    public StorageAmbiguousInitiationException(String message) {
        super(DEFAULT_ERROR_CODE, message);
    }

    public StorageAmbiguousInitiationException(String message, Throwable cause) {
        super(DEFAULT_ERROR_CODE, message, cause);
    }
}
