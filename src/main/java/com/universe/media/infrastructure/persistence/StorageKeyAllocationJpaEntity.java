package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(
        name = "media_storage_key_allocations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_media_storage_key_allocations_provider_key",
                        columnNames = {
                                "storage_provider_id",
                                "storage_key"
                        }
                ),
                @UniqueConstraint(
                        name = "uq_media_storage_key_allocations_identity",
                        columnNames = {
                                "id",
                                "storage_provider_id",
                                "storage_key"
                        }
                ),
                @UniqueConstraint(
                        name = "uq_media_storage_key_allocations_full_location",
                        columnNames = {
                                "id",
                                "storage_provider_id",
                                "storage_bucket_name",
                                "storage_key"
                        }
                )
        }
)
public class StorageKeyAllocationJpaEntity implements Persistable<String> {

    @Id
    @Column(
            name = "id",
            nullable = false,
            updatable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "storage_provider_id",
            nullable = false,
            updatable = false,
            length = 50
    )
    private String storageProviderId;

    @Column(
            name = "storage_bucket_name",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String storageBucketName;

    @Column(
            name = "storage_key",
            nullable = false,
            updatable = false,
            length = 500
    )
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "allocation_status",
            nullable = false,
            length = 20
    )
    private StorageKeyAllocationStatus allocationStatus;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "allocated_by_type",
            nullable = false,
            length = 50
    )
    private StorageKeyAllocationType allocatedByType;

    @Column(
            name = "allocated_by_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String allocatedById;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    @jakarta.persistence.Version
    @Column(
            name = "persistence_version",
            nullable = false
    )
    private Long persistenceVersion;

    @Transient
    private boolean isNew = true;

    public StorageKeyAllocationJpaEntity() {
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getStorageProviderId() {
        return storageProviderId;
    }

    public void setStorageProviderId(String storageProviderId) {
        this.storageProviderId = storageProviderId;
    }

    public String getStorageBucketName() {
        return storageBucketName;
    }

    public void setStorageBucketName(String storageBucketName) {
        this.storageBucketName = storageBucketName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public StorageKeyAllocationStatus getAllocationStatus() {
        return allocationStatus;
    }

    public void setAllocationStatus(StorageKeyAllocationStatus allocationStatus) {
        this.allocationStatus = allocationStatus;
    }

    public StorageKeyAllocationType getAllocatedByType() {
        return allocatedByType;
    }

    public void setAllocatedByType(StorageKeyAllocationType allocatedByType) {
        this.allocatedByType = allocatedByType;
    }

    public String getAllocatedById() {
        return allocatedById;
    }

    public void setAllocatedById(String allocatedById) {
        this.allocatedById = allocatedById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }

    public void setPersistenceVersion(Long persistenceVersion) {
        this.persistenceVersion = persistenceVersion;
    }
}
