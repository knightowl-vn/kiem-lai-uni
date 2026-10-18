package com.universe.media.application.exceptions;

/**
 * Thrown when the outcome of a provider multipart completion call cannot be determined
 * deterministically (e.g. HTTP 5xx, socket timeout, or transport interruption).
 *
 * <p>Such ambiguous outcomes indicate that the remote provider may or may not have assembled
 * the final object, permitting a guarded provenance probe of the storage location before
 * committing to failure or registration.</p>
 */
public class StorageAmbiguousCompletionException extends StorageException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "MEDIA_STORAGE_COMPLETION_AMBIGUOUS";

    public StorageAmbiguousCompletionException(String message) {
        super(DEFAULT_ERROR_CODE, message);
    }

    public StorageAmbiguousCompletionException(String message, Throwable cause) {
        super(DEFAULT_ERROR_CODE, message, cause);
    }
}
