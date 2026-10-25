package com.universe.media.application.exceptions;

/**
 * Thrown when a storage provider definitively rejects a multipart initiation request
 * before any remote upload or object could be created (e.g. MalformedAccessKeyId,
 * InvalidAccessKeyId, SignatureDoesNotMatch, NoSuchBucket).
 *
 * <p>Because this failure is conclusively pre-execution, no remote multipart upload
 * exists on the provider, permitting safe compensation of local initiation claims.</p>
 */
public class StorageInitiationRejectedException extends StorageException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "MEDIA_STORAGE_INITIATION_REJECTED";

    public StorageInitiationRejectedException(String message) {
        super(DEFAULT_ERROR_CODE, message);
    }

    public StorageInitiationRejectedException(String message, Throwable cause) {
        super(DEFAULT_ERROR_CODE, message, cause);
    }
}
