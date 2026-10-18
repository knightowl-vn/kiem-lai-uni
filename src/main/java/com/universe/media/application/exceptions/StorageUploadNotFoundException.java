package com.universe.media.application.exceptions;

import com.universe.media.domain.StorageKey;

import java.util.Objects;

/**
 * Thrown when a multipart upload ID is not found on the remote storage provider (e.g. {@code NoSuchUpload}).
 */
public class StorageUploadNotFoundException extends StorageObjectNotFoundException {

    private static final long serialVersionUID = 1L;
    private static final String ERROR_CODE = "MEDIA_STORAGE_UPLOAD_NOT_FOUND";

    private final String uploadId;

    public StorageUploadNotFoundException(
            StorageKey key,
            String uploadId
    ) {
        super(
                Objects.requireNonNull(key, "StorageKey cannot be null.")
        );
        this.uploadId = Objects.requireNonNull(uploadId, "uploadId cannot be null.");
    }

    public StorageUploadNotFoundException(
            StorageKey key,
            String uploadId,
            Throwable cause
    ) {
        super(
                Objects.requireNonNull(key, "StorageKey cannot be null."),
                cause
        );
        this.uploadId = Objects.requireNonNull(uploadId, "uploadId cannot be null.");
    }

    public String getUploadId() {
        return uploadId;
    }
}
