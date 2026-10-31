package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.UploadSessionCleanupStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "media_upload_sessions")
public class MediaUploadSessionJpaEntity implements Persistable<String> {

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
            name = "initiator_principal_id",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String initiatorPrincipalId;

    @Column(
            name = "consumer_context",
            nullable = false,
            updatable = false,
            length = 50
    )
    private String consumerContext;

    @Column(
            name = "target_entity_type",
            nullable = false,
            updatable = false,
            length = 50
    )
    private String targetEntityType;

    @Column(
            name = "target_entity_id",
            nullable = false,
            updatable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String targetEntityId;

    @Column(
            name = "idempotency_key",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String idempotencyKey;

    @Column(
            name = "media_type",
            nullable = false,
            updatable = false,
            length = 20
    )
    private String mediaType;

    @Column(
            name = "declared_mime_type",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String declaredMimeType;

    @Column(
            name = "declared_size_bytes",
            nullable = false,
            updatable = false
    )
    private long declaredSizeBytes;

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

    @Column(
            name = "storage_allocation_id",
            nullable = false,
            updatable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String storageAllocationId;

    @Column(
            name = "provider_upload_id",
            length = 1024
    )
    private String providerUploadId;

    @Column(
            name = "part_size_bytes",
            nullable = false,
            updatable = false
    )
    private long partSizeBytes;

    @Column(
            name = "expected_part_count",
            nullable = false,
            updatable = false
    )
    private int expectedPartCount;

    @Column(
            name = "declared_content_hash",
            length = 64,
            columnDefinition = "CHAR(64)"
    )
    private String declaredContentHash;

    @Column(
            name = "manifest_hash",
            length = 64,
            columnDefinition = "CHAR(64)"
    )
    private String manifestHash;

    @Column(
            name = "original_filename",
            nullable = false,
            updatable = false,
            length = 255
    )
    private String originalFilename;

    @Column(
            name = "client_tag",
            updatable = false,
            length = 64
    )
    private String clientTag;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private MediaUploadSessionStatus status;

    @Column(
            name = "expires_at",
            nullable = false,
            updatable = false
    )
    private Instant expiresAt;

    @Column(
            name = "lease_owner",
            length = 100
    )
    private String leaseOwner;

    @Column(
            name = "lease_expires_at"
    )
    private Instant leaseExpiresAt;

    @Column(
            name = "registered_asset_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String registeredAssetId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "cleanup_status",
            nullable = false,
            length = 20
    )
    private UploadSessionCleanupStatus cleanupStatus;

    @Column(
            name = "cleanup_retry_count",
            nullable = false
    )
    private int cleanupRetryCount;

    @Column(
            name = "cleanup_last_attempt_at"
    )
    private Instant cleanupLastAttemptAt;

    @Column(
            name = "cleanup_error_detail",
            length = 1000
    )
    private String cleanupErrorDetail;

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

    @Column(
            name = "completed_at"
    )
    private Instant completedAt;

    @Version
    @Column(
            name = "persistence_version",
            nullable = false
    )
    private Long persistenceVersion;

    @Transient
    private boolean isNew = true;

    public MediaUploadSessionJpaEntity() {
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

    public String getInitiatorPrincipalId() {
        return initiatorPrincipalId;
    }

    public void setInitiatorPrincipalId(String initiatorPrincipalId) {
        this.initiatorPrincipalId = initiatorPrincipalId;
    }

    public String getConsumerContext() {
        return consumerContext;
    }

    public void setConsumerContext(String consumerContext) {
        this.consumerContext = consumerContext;
    }

    public String getTargetEntityType() {
        return targetEntityType;
    }

    public void setTargetEntityType(String targetEntityType) {
        this.targetEntityType = targetEntityType;
    }

    public String getTargetEntityId() {
        return targetEntityId;
    }

    public void setTargetEntityId(String targetEntityId) {
        this.targetEntityId = targetEntityId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getDeclaredMimeType() {
        return declaredMimeType;
    }

    public void setDeclaredMimeType(String declaredMimeType) {
        this.declaredMimeType = declaredMimeType;
    }

    public long getDeclaredSizeBytes() {
        return declaredSizeBytes;
    }

    public void setDeclaredSizeBytes(long declaredSizeBytes) {
        this.declaredSizeBytes = declaredSizeBytes;
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

    public String getStorageAllocationId() {
        return storageAllocationId;
    }

    public void setStorageAllocationId(String storageAllocationId) {
        this.storageAllocationId = storageAllocationId;
    }

    public String getProviderUploadId() {
        return providerUploadId;
    }

    public void setProviderUploadId(String providerUploadId) {
        this.providerUploadId = providerUploadId;
    }

    public long getPartSizeBytes() {
        return partSizeBytes;
    }

    public void setPartSizeBytes(long partSizeBytes) {
        this.partSizeBytes = partSizeBytes;
    }

    public int getExpectedPartCount() {
        return expectedPartCount;
    }

    public void setExpectedPartCount(int expectedPartCount) {
        this.expectedPartCount = expectedPartCount;
    }

    public String getDeclaredContentHash() {
        return declaredContentHash;
    }

    public void setDeclaredContentHash(String declaredContentHash) {
        this.declaredContentHash = declaredContentHash;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public void setManifestHash(String manifestHash) {
        this.manifestHash = manifestHash;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getClientTag() {
        return clientTag;
    }

    public void setClientTag(String clientTag) {
        this.clientTag = clientTag;
    }

    public MediaUploadSessionStatus getStatus() {
        return status;
    }

    public void setStatus(MediaUploadSessionStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public String getRegisteredAssetId() {
        return registeredAssetId;
    }

    public void setRegisteredAssetId(String registeredAssetId) {
        this.registeredAssetId = registeredAssetId;
    }

    public UploadSessionCleanupStatus getCleanupStatus() {
        return cleanupStatus;
    }

    public void setCleanupStatus(UploadSessionCleanupStatus cleanupStatus) {
        this.cleanupStatus = cleanupStatus;
    }

    public int getCleanupRetryCount() {
        return cleanupRetryCount;
    }

    public void setCleanupRetryCount(int cleanupRetryCount) {
        this.cleanupRetryCount = cleanupRetryCount;
    }

    public Instant getCleanupLastAttemptAt() {
        return cleanupLastAttemptAt;
    }

    public void setCleanupLastAttemptAt(Instant cleanupLastAttemptAt) {
        this.cleanupLastAttemptAt = cleanupLastAttemptAt;
    }

    public String getCleanupErrorDetail() {
        return cleanupErrorDetail;
    }

    public void setCleanupErrorDetail(String cleanupErrorDetail) {
        this.cleanupErrorDetail = cleanupErrorDetail;
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }

    public void setPersistenceVersion(Long persistenceVersion) {
        this.persistenceVersion = persistenceVersion;
    }
}
