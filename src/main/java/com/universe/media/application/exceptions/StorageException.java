package com.universe.media.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Base application exception for binary storage operations.
 */
public class StorageException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "MEDIA_STORAGE_FAILURE";

    public StorageException(
            String message
    ) {
        super(DEFAULT_ERROR_CODE, message);
    }

    public StorageException(
            String message,
            Throwable cause
    ) {
        super(DEFAULT_ERROR_CODE, message, cause);
    }

    protected StorageException(
            String errorCode,
            String message
    ) {
        super(errorCode, message);
    }

    protected StorageException(
            String errorCode,
            String message,
            Throwable cause
    ) {
        super(errorCode, message, cause);
    }
}
