package com.universe.media.application.exceptions;

import com.universe.media.domain.StorageKey;

import java.util.Objects;

/**
 * Thrown when attempting to store a binary payload with a key that already exists.
 * Storage store operations are create-only and must never overwrite existing objects.
 */
public class StorageObjectAlreadyExistsException extends StorageException {

    private static final long serialVersionUID = 1L;
    private static final String ERROR_CODE = "MEDIA_STORAGE_OBJECT_ALREADY_EXISTS";

    public StorageObjectAlreadyExistsException(
            StorageKey key
    ) {
        super(
                ERROR_CODE,
                createMessage(key)
        );
    }

    public StorageObjectAlreadyExistsException(
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
        return "Storage object already exists for key: "
                + Objects.requireNonNull(key, "StorageKey cannot be null.").value();
    }
}
