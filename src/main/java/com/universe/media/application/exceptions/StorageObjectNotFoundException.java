package com.universe.media.application.exceptions;

import com.universe.media.domain.StorageKey;

import java.util.Objects;

/**
 * Thrown when an operation targets a binary storage object that does not exist.
 */
public class StorageObjectNotFoundException extends StorageException {

    private static final long serialVersionUID = 1L;
    private static final String ERROR_CODE = "MEDIA_STORAGE_OBJECT_NOT_FOUND";

    public StorageObjectNotFoundException(
            StorageKey key
    ) {
        super(
                ERROR_CODE,
                createMessage(key)
        );
    }

    public StorageObjectNotFoundException(
            StorageKey key,
            Throwable cause
    ) {
        super(
                ERROR_CODE,
                createMessage(key),
                cause
        );
    }

    private static String createMessage(
            StorageKey key
    ) {
        return "Storage object not found for key: "
                + Objects.requireNonNull(key, "StorageKey cannot be null.").value();
    }
}
