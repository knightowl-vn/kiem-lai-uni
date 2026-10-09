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
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
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
     * Permanently marks a storage key allocation as tombstoned within the current transaction.
     * Enforces atomic rollback/commit when invoked from composite business operations (such as session abort).
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

    /**
     * Permanently marks a storage key allocation as tombstoned in an isolated independent transaction.
     * Use when compensating a reservation from an exception handler where the outer transaction may be marked for rollback.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void tombstoneAllocationInNewTransaction(UUID allocationId) {
        tombstoneAllocation(allocationId);
    }

    /**
     * Looks up an existing allocation by its storage provider and key.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<StorageKeyAllocation> findByStorageLocation(StorageProviderId providerId, StorageKey key) {
        Objects.requireNonNull(providerId, "StorageProviderId cannot be null.");
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        return repositoryPort.findByStorageLocation(providerId, key);
    }

    /**
     * Permanently marks a storage key allocation as tombstoned by its storage location.
     * If no allocation exists (e.g. historical unbacked object), safely completes without error.
     */
    @Transactional
    public void tombstoneAllocationByLocation(StorageProviderId providerId, StorageKey key) {
        Objects.requireNonNull(providerId, "StorageProviderId cannot be null.");
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        repositoryPort.findByStorageLocation(providerId, key).ifPresent(allocation -> {
            Instant now = clockPort.now();
            allocation.tombstone(now);
            repositoryPort.save(allocation);
        });
    }
}
