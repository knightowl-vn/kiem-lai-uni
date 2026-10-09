package com.universe.media.infrastructure.persistence;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.ports.StorageKeyAllocationRepositoryPort;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class StorageKeyAllocationPersistenceAdapter implements StorageKeyAllocationRepositoryPort {

    private static final String UQ_PROVIDER_KEY = "uq_media_storage_key_allocations_provider_key";

    private final SpringDataStorageKeyAllocationJpaRepository repository;

    public StorageKeyAllocationPersistenceAdapter(
            SpringDataStorageKeyAllocationJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataStorageKeyAllocationJpaRepository cannot be null.");
    }

    @Override
    public Optional<StorageKeyAllocation> findById(UUID id) {
        Objects.requireNonNull(id, "Allocation ID cannot be null.");
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<StorageKeyAllocation> findByStorageLocation(StorageProviderId providerId, StorageKey key) {
        Objects.requireNonNull(providerId, "ProviderId cannot be null.");
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        return repository.findByStorageProviderIdAndStorageKey(providerId.value(), key.value())
                .map(this::toDomain);
    }

    @Override
    public boolean existsByStorageLocation(StorageProviderId providerId, StorageKey key) {
        Objects.requireNonNull(providerId, "ProviderId cannot be null.");
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        return repository.existsByStorageProviderIdAndStorageKey(providerId.value(), key.value());
    }

    @Override
    public StorageKeyAllocation save(StorageKeyAllocation allocation) {
        Objects.requireNonNull(allocation, "StorageKeyAllocation cannot be null.");

        String allocationId = allocation.getId().toString();
        Optional<StorageKeyAllocationJpaEntity> existingOpt = repository.findById(allocationId);

        StorageKeyAllocationJpaEntity entity;
        if (existingOpt.isPresent()) {
            entity = existingOpt.get();

            // 1. Existing allocations require valid version evidence
            if (allocation.getPersistenceVersion() == null) {
                throw new org.springframework.dao.OptimisticLockingFailureException(
                        String.format("Cannot update existing storage key allocation %s without version evidence (persistenceVersion is null).", allocationId)
                );
            }

            // 2. Reject stale versions before mutation
            if (!Objects.equals(allocation.getPersistenceVersion(), entity.getPersistenceVersion())) {
                throw new org.springframework.dao.OptimisticLockingFailureException(
                        String.format(
                                "Storage key allocation %s was concurrently modified. Expected version %d but found %d.",
                                allocationId, allocation.getPersistenceVersion(), entity.getPersistenceVersion()
                        )
                );
            }

            // 3. Validate persisted allocation state and ownership before applying changes
            StorageKeyAllocationStatus persistedStatus = entity.getAllocationStatus();
            StorageKeyAllocationStatus incomingStatus = allocation.getStatus();

            boolean ownerMatchesPersisted = entity.getAllocatedByType() == allocation.getAllocatedByType()
                    && entity.getAllocatedById().equals(allocation.getAllocatedById().toString());

            switch (persistedStatus) {
                case RESERVED -> {
                    if (incomingStatus == StorageKeyAllocationStatus.RESERVED) {
                        if (!ownerMatchesPersisted) {
                            throw new IllegalStateException(
                                    String.format(
                                            "Cannot rewrite ownership of RESERVED storage key allocation %s within same state. Persisted: %s/%s, attempted: %s/%s",
                                            allocationId, entity.getAllocatedByType(), entity.getAllocatedById(),
                                            allocation.getAllocatedByType(), allocation.getAllocatedById()
                                    )
                            );
                        }
                    } else if (incomingStatus == StorageKeyAllocationStatus.COMMITTED) {
                        // Authorized ownership transfer from reservation to committed target
                    } else if (incomingStatus == StorageKeyAllocationStatus.TOMBSTONE) {
                        if (!ownerMatchesPersisted) {
                            throw new IllegalStateException(
                                    String.format(
                                            "Cannot rewrite owner when transitioning RESERVED storage key allocation %s to TOMBSTONE. Original reservation owner %s/%s must be preserved; attempted: %s/%s",
                                            allocationId, entity.getAllocatedByType(), entity.getAllocatedById(),
                                            allocation.getAllocatedByType(), allocation.getAllocatedById()
                                    )
                            );
                        }
                    }
                }
                case COMMITTED -> {
                    if (incomingStatus == StorageKeyAllocationStatus.RESERVED) {
                        throw new IllegalStateException(
                                String.format("Cannot regress persisted COMMITTED storage key allocation %s back to RESERVED.", allocationId)
                        );
                    } else if (incomingStatus == StorageKeyAllocationStatus.COMMITTED) {
                        if (!ownerMatchesPersisted) {
                            throw new IllegalStateException(
                                    String.format(
                                            "Cannot reassign persisted COMMITTED storage key allocation %s to a different target. Currently committed to %s/%s, attempted: %s/%s",
                                            allocationId, entity.getAllocatedByType(), entity.getAllocatedById(),
                                            allocation.getAllocatedByType(), allocation.getAllocatedById()
                                    )
                            );
                        }
                    } else if (incomingStatus == StorageKeyAllocationStatus.TOMBSTONE) {
                        if (!ownerMatchesPersisted) {
                            throw new IllegalStateException(
                                    String.format(
                                            "Cannot rewrite owner when transitioning COMMITTED storage key allocation %s to TOMBSTONE. Committed owner %s/%s must be preserved; attempted: %s/%s",
                                            allocationId, entity.getAllocatedByType(), entity.getAllocatedById(),
                                            allocation.getAllocatedByType(), allocation.getAllocatedById()
                                    )
                            );
                        }
                    }
                }
                case TOMBSTONE -> {
                    if (incomingStatus != StorageKeyAllocationStatus.TOMBSTONE) {
                        throw new IllegalStateException(
                                String.format("Cannot mutate persisted TOMBSTONE storage key allocation %s to %s; TOMBSTONE is terminal.", allocationId, incomingStatus)
                        );
                    }
                    if (!ownerMatchesPersisted) {
                        throw new IllegalStateException(
                                String.format(
                                        "Cannot rewrite owner of persisted TOMBSTONE storage key allocation %s. Persisted owner %s/%s must remain immutable; attempted: %s/%s",
                                        allocationId, entity.getAllocatedByType(), entity.getAllocatedById(),
                                        allocation.getAllocatedByType(), allocation.getAllocatedById()
                                )
                        );
                    }
                }
            }

            // Verify immutable fields have not been tampered with
            if (!entity.getStorageProviderId().equals(allocation.getProviderId().value())
                    || !entity.getStorageKey().equals(allocation.getStorageKey().value())
                    || !Objects.equals(entity.getStorageBucketName(), allocation.getBucketName())
                    || !entity.getCreatedAt().equals(allocation.getCreatedAt())) {
                throw new IllegalStateException(
                        String.format("Immutable storage key properties cannot be altered for allocation %s.", allocationId)
                );
            }

            // Apply valid lifecycle mutation
            entity.setAllocationStatus(incomingStatus);
            entity.setAllocatedByType(allocation.getAllocatedByType());
            entity.setAllocatedById(allocation.getAllocatedById().toString());
            entity.setUpdatedAt(allocation.getUpdatedAt());
        } else {
            // Only genuinely new allocations may be created without an existing persistence version
            if (allocation.getPersistenceVersion() != null) {
                throw new org.springframework.dao.OptimisticLockingFailureException(
                        String.format("Cannot create a new allocation %s with a non-null historical persistence version (%d). Row is missing.",
                                allocationId, allocation.getPersistenceVersion())
                );
            }

            entity = new StorageKeyAllocationJpaEntity();
            entity.setId(allocationId);
            entity.setStorageProviderId(allocation.getProviderId().value());
            entity.setStorageBucketName(allocation.getBucketName());
            entity.setStorageKey(allocation.getStorageKey().value());
            entity.setAllocationStatus(allocation.getStatus());
            entity.setAllocatedByType(allocation.getAllocatedByType());
            entity.setAllocatedById(allocation.getAllocatedById().toString());
            entity.setCreatedAt(allocation.getCreatedAt());
            entity.setUpdatedAt(allocation.getUpdatedAt());
        }

        try {
            StorageKeyAllocationJpaEntity saved = repository.saveAndFlush(entity);
            return toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isConstraintViolation(ex, UQ_PROVIDER_KEY)) {
                throw new DuplicateStorageLocationException(
                        StorageLocation.of(allocation.getProviderId(), allocation.getStorageKey())
                );
            }
            throw ex;
        }
    }

    private boolean isConstraintViolation(DataIntegrityViolationException ex, String constraintName) {
        String target = constraintName.toLowerCase();
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                if (cve.getConstraintName() != null && cve.getConstraintName().toLowerCase().contains(target)) {
                    return true;
                }
            }
            if (current.getMessage() != null && current.getMessage().toLowerCase().contains(target)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private StorageKeyAllocation toDomain(StorageKeyAllocationJpaEntity entity) {
        return StorageKeyAllocation.rehydrate(
                UUID.fromString(entity.getId()),
                StorageProviderId.of(entity.getStorageProviderId()),
                entity.getStorageBucketName(),
                StorageKey.of(entity.getStorageKey()),
                entity.getAllocationStatus(),
                entity.getAllocatedByType(),
                UUID.fromString(entity.getAllocatedById()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPersistenceVersion()
        );
    }
}
