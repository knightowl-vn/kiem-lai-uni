package com.universe.media.application.ports.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.domain.StorageProviderId;

/**
 * Port for resolving the appropriate {@link BinaryStoragePort} instance for a given {@link StorageProviderId}.
 */
public interface StorageProviderResolverPort {

    /**
     * Resolves the {@link BinaryStoragePort} associated with the specified provider identifier.
     *
     * @param providerId the storage provider identifier
     * @return the matching active binary storage port implementation
     * @throws StorageException if no configured provider exists for the specified identifier
     */
    BinaryStoragePort resolve(StorageProviderId providerId);
}
