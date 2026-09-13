package com.universe.media.application.exceptions;

import com.universe.media.domain.StorageLocation;
import com.universe.shared.exceptions.BaseApplicationException;

import java.util.Objects;

public class DuplicateStorageLocationException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public DuplicateStorageLocationException(
            StorageLocation storageLocation
    ) {
        super(
                "MEDIA_DUPLICATE_STORAGE_LOCATION",
                "Storage location is already in use: provider="
                        + Objects.requireNonNull(storageLocation, "StorageLocation cannot be null.").providerId().value()
                        + ", key="
                        + storageLocation.key().value()
        );
    }
}
