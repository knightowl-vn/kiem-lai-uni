package com.universe.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an allocated or reserved storage key in the Media module.
 *
 * <p>Enforces invariant rules:
 * <ul>
 *   <li>Immutable storage key and provider identification;</li>
 *   <li>Explicit state transitions: RESERVED -&gt; COMMITTED or TOMBSTONE;</li>
 *   <li>Permanent tombstone: once marked TOMBSTONE, can NEVER be transitioned back or re-reserved;</li>
 *   <li>Caller-supplied timestamps;</li>
 * </ul>
 */
public final class StorageKeyAllocation {

    public static final String CANONICAL_BUCKETLESS_SENTINEL = "NONE";

    private final UUID id;
    private final StorageProviderId providerId;
    private final String bucketName;
    private final StorageKey storageKey;
    private StorageKeyAllocationStatus status;
    private StorageKeyAllocationType allocatedByType;
    private UUID allocatedById;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long persistenceVersion;

    private StorageKeyAllocation(
            UUID id,
            StorageProviderId providerId,
            String bucketName,
            StorageKey storageKey,
            StorageKeyAllocationStatus status,
            StorageKeyAllocationType allocatedByType,
            UUID allocatedById,
            Instant createdAt,
            Instant updatedAt,
            Long persistenceVersion
    ) {
        this.id = Objects.requireNonNull(id, "Allocation ID cannot be null.");
        this.providerId = Objects.requireNonNull(providerId, "ProviderId cannot be null.");
        this.bucketName = Objects.requireNonNull(bucketName, "BucketName cannot be null.");
        this.storageKey = Objects.requireNonNull(storageKey, "StorageKey cannot be null.");
        this.status = Objects.requireNonNull(status, "Status cannot be null.");
        this.allocatedByType = Objects.requireNonNull(allocatedByType, "AllocatedByType cannot be null.");
        this.allocatedById = Objects.requireNonNull(allocatedById, "AllocatedById cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "UpdatedAt cannot be null.");
        this.persistenceVersion = persistenceVersion;

        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
    }

    /**
     * Canonicalizes bucket names, mapping absent/blank values to {@link #CANONICAL_BUCKETLESS_SENTINEL}
     * while enforcing that bucket-requiring providers (such as b2) provide an authentic bucket name.
     */
    public static String canonicalizeBucket(StorageProviderId providerId, String bucketName) {
        Objects.requireNonNull(providerId, "ProviderId cannot be null.");
        if (bucketName == null || bucketName.isBlank() || CANONICAL_BUCKETLESS_SENTINEL.equalsIgnoreCase(bucketName.trim())) {
            if ("b2".equalsIgnoreCase(providerId.value())) {
                throw new IllegalArgumentException("Storage provider 'b2' requires a valid non-blank bucket name and cannot use the bucketless sentinel.");
            }
            return CANONICAL_BUCKETLESS_SENTINEL;
        }
        return bucketName.trim();
    }

    /**
     * Factory method for initial key reservation.
     */
    public static StorageKeyAllocation reserve(
            UUID id,
            StorageProviderId providerId,
            String bucketName,
            StorageKey storageKey,
            StorageKeyAllocationType allocatedByType,
            UUID allocatedById,
            Instant createdAt
    ) {
        String canonicalBucket = canonicalizeBucket(providerId, bucketName);
        return new StorageKeyAllocation(
                id,
                providerId,
                canonicalBucket,
                storageKey,
                StorageKeyAllocationStatus.RESERVED,
                allocatedByType,
                allocatedById,
                createdAt,
                createdAt,
                null
        );
    }

    /**
     * Factory method for rehydrating from persistence with optimistic lock version.
     */
    public static StorageKeyAllocation rehydrate(
            UUID id,
            StorageProviderId providerId,
            String bucketName,
            StorageKey storageKey,
            StorageKeyAllocationStatus status,
            StorageKeyAllocationType allocatedByType,
            UUID allocatedById,
            Instant createdAt,
            Instant updatedAt,
            Long persistenceVersion
    ) {
        String canonicalBucket = (bucketName == null || bucketName.isBlank() || CANONICAL_BUCKETLESS_SENTINEL.equalsIgnoreCase(bucketName.trim()))
                ? CANONICAL_BUCKETLESS_SENTINEL
                : bucketName.trim();
        return new StorageKeyAllocation(
                id,
                providerId,
                canonicalBucket,
                storageKey,
                status,
                allocatedByType,
                allocatedById,
                createdAt,
                updatedAt,
                persistenceVersion
        );
    }

    /**
     * Factory method for rehydrating from persistence without explicit version.
     */
    public static StorageKeyAllocation rehydrate(
            UUID id,
            StorageProviderId providerId,
            String bucketName,
            StorageKey storageKey,
            StorageKeyAllocationStatus status,
            StorageKeyAllocationType allocatedByType,
            UUID allocatedById,
            Instant createdAt,
            Instant updatedAt
    ) {
        return rehydrate(
                id,
                providerId,
                bucketName,
                storageKey,
                status,
                allocatedByType,
                allocatedById,
                createdAt,
                updatedAt,
                null
        );
    }

    /**
     * Commits this allocation to a durable target (e.g. MediaAssetVersion or MediaImageVariant).
     *
     * <p>Contract:
     * <ul>
     *   <li>RESERVED -&gt; COMMITTED for one target.</li>
     *   <li>Idempotent repeat commit for identical target is a safe no-op.</li>
     *   <li>Reassignment of a COMMITTED allocation to another owner/target is strictly prohibited.</li>
     *   <li>Committing a TOMBSTONE allocation is strictly prohibited.</li>
     * </ul>
     */
    public void commit(
            StorageKeyAllocationType newType,
            UUID newTargetId,
            Instant authoritativeUpdatedAt
    ) {
        Objects.requireNonNull(newType, "New allocation type cannot be null.");
        Objects.requireNonNull(newTargetId, "New target ID cannot be null.");
        Objects.requireNonNull(authoritativeUpdatedAt, "Authoritative update timestamp cannot be null.");

        if (this.status == StorageKeyAllocationStatus.TOMBSTONE) {
            throw new IllegalStateException("Cannot commit a tombstoned storage key allocation.");
        }

        if (this.status == StorageKeyAllocationStatus.COMMITTED) {
            if (this.allocatedByType != newType || !this.allocatedById.equals(newTargetId)) {
                throw new IllegalStateException(
                        String.format(
                                "Cannot reassign a COMMITTED storage key allocation to a different target. Currently committed to %s/%s, attempted: %s/%s",
                                this.allocatedByType, this.allocatedById, newType, newTargetId
                        )
                );
            }
            // Idempotent repeat commit for identical target: safe no-op
            return;
        }

        this.status = StorageKeyAllocationStatus.COMMITTED;
        this.allocatedByType = newType;
        this.allocatedById = newTargetId;
        this.updatedAt = authoritativeUpdatedAt;
    }

    /**
     * Marks this allocation as permanently dead (tombstoned). The key can never be re-used.
     * TOMBSTONE is terminal. Idempotent if already tombstoned.
     */
    public void tombstone(Instant authoritativeUpdatedAt) {
        Objects.requireNonNull(authoritativeUpdatedAt, "Authoritative update timestamp cannot be null.");
        if (this.status == StorageKeyAllocationStatus.TOMBSTONE) {
            return; // Idempotent no-op
        }
        this.status = StorageKeyAllocationStatus.TOMBSTONE;
        this.updatedAt = authoritativeUpdatedAt;
    }

    public UUID getId() {
        return id;
    }

    public StorageProviderId getProviderId() {
        return providerId;
    }

    public String getBucketName() {
        return bucketName;
    }

    public boolean isBucketless() {
        return CANONICAL_BUCKETLESS_SENTINEL.equals(bucketName);
    }

    public java.util.Optional<String> getCloudBucketName() {
        return isBucketless() ? java.util.Optional.empty() : java.util.Optional.of(bucketName);
    }

    public StorageKey getStorageKey() {
        return storageKey;
    }

    public StorageKeyAllocationStatus getStatus() {
        return status;
    }

    public StorageKeyAllocationType getAllocatedByType() {
        return allocatedByType;
    }

    public UUID getAllocatedById() {
        return allocatedById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }
}
