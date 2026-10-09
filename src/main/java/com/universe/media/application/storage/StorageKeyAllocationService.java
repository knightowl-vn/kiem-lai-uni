package com.universe.media.application.storage;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.ports.StorageKeyAllocationRepositoryPort;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service for orchestrating atomic storage key allocations, ownership transfer, and tombstone lifecycle.
 */
@Service
public class StorageKeyAllocationService {

    private final StorageKeyAllocationRepositoryPort repositoryPort;
    private final ClockPort clockPort;

    public StorageKeyAllocationService(
            StorageKeyAllocationRepositoryPort repositoryPort,
            ClockPort clockPort
    ) {
        this.repositoryPort = Objects.requireNonNull(repositoryPort, "StorageKeyAllocationRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    /**
     * Atomically reserves a globally unique storage key for an upload session or direct asset creation.
     */
    @Transactional
    public StorageKeyAllocation reserveKey(
            StorageProviderId providerId,
            String bucketName,
            StorageKey storageKey,
            StorageKeyAllocationType allocatedByType,
            UUID ownerId
    ) {
        Objects.requireNonNull(providerId, "StorageProviderId cannot be null.");
        Objects.requireNonNull(storageKey, "StorageKey cannot be null.");
        Objects.requireNonNull(allocatedByType, "StorageKeyAllocationType cannot be null.");
        Objects.requireNonNull(ownerId, "Owner ID cannot be null.");

        if (repositoryPort.existsByStorageLocation(providerId, storageKey)) {
            throw new DuplicateStorageLocationException(StorageLocation.of(providerId, storageKey));
        }

        UUID allocationId = UUID.randomUUID();
        Instant now = clockPort.now();

        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                allocatedByType,
                ownerId,
                now
        );

        return repositoryPort.save(allocation);
    }

    /**
     * Commits an existing key reservation to a completed asset version or image variant.
     */
    @Transactional
    public StorageKeyAllocation commitAllocation(
            UUID allocationId,
            StorageKeyAllocationType targetType,
            UUID targetId
    ) {
        Objects.requireNonNull(allocationId, "Allocation ID cannot be null.");
        Objects.requireNonNull(targetType, "Target allocation type cannot be null.");
        Objects.requireNonNull(targetId, "Target ID cannot be null.");

        StorageKeyAllocation allocation = repositoryPort.findById(allocationId)
                .orElseThrow(() -> new IllegalArgumentException("Storage key allocation not found for ID: " + allocationId));

        Instant now = clockPort.now();
        allocation.commit(targetType, targetId, now);

        return repositoryPort.save(allocation);
    }

    /**
     * Permanently marks a storage key allocation as tombstoned.
     */
    @Transactional
    public void tombstoneAllocation(UUID allocationId) {
        Objects.requireNonNull(allocationId, "Allocation ID cannot be null.");

        StorageKeyAllocation allocation = repositoryPort.findById(allocationId)
                .orElseThrow(() -> new IllegalArgumentException("Storage key allocation not found for ID: " + allocationId));

        Instant now = clockPort.now();
        allocation.tombstone(now);

        repositoryPort.save(allocation);
    }
}
