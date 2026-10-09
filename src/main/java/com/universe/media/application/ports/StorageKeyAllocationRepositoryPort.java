package com.universe.media.application.ports;

import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageProviderId;

import java.util.Optional;
import java.util.UUID;

/**
 * Output port for persistent management of storage key allocations.
 */
public interface StorageKeyAllocationRepositoryPort {

    Optional<StorageKeyAllocation> findById(UUID id);

    Optional<StorageKeyAllocation> findByStorageLocation(StorageProviderId providerId, StorageKey key);

    boolean existsByStorageLocation(StorageProviderId providerId, StorageKey key);

    StorageKeyAllocation save(StorageKeyAllocation allocation);
}
