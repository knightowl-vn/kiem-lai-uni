package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaVideoHlsPackageStatus;
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
@Table(name = "media_video_hls_packages")
public class MediaVideoHlsPackageJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "asset_id", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String assetId;

    @Column(name = "source_version_id", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String sourceVersionId;

    @Column(name = "source_version_number", nullable = false, updatable = false)
    private int sourceVersionNumber;

    @Column(name = "package_uuid", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String packageUuid;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "storage_provider_id", nullable = false, updatable = false, length = 50)
    private String storageProviderId;

    @Column(name = "storage_bucket_name", nullable = false, updatable = false, length = 100)
    private String storageBucketName;

    @Column(name = "base_prefix", nullable = false, updatable = false, length = 500)
    private String basePrefix;

    @Column(name = "master_manifest_key", nullable = false, updatable = false, length = 500)
    private String masterManifestKey;

    @Column(name = "hls_version", nullable = false, updatable = false)
    private int hlsVersion;

    @Column(name = "segment_format", nullable = false, updatable = false, length = 20)
    private String segmentFormat;

    @Column(name = "segment_duration_seconds", nullable = false, updatable = false)
    private int segmentDurationSeconds;

    @Column(name = "renditions_json", length = 2000)
    private String renditionsJson;

    @Column(name = "master_manifest_sha256", length = 64, columnDefinition = "CHAR(64)")
    private String masterManifestSha256;

    @Column(name = "total_size_bytes")
    private Long totalSizeBytes;

    @Column(name = "object_count")
    private Integer objectCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MediaVideoHlsPackageStatus status;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "purge_after")
    private Instant purgeAfter;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "persistence_version", nullable = false)
    private Long persistenceVersion;

    @Transient
    private boolean isNew = true;

    public MediaVideoHlsPackageJpaEntity() {
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

    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(String sourceVersionId) { this.sourceVersionId = sourceVersionId; }
    public int getSourceVersionNumber() { return sourceVersionNumber; }
    public void setSourceVersionNumber(int sourceVersionNumber) { this.sourceVersionNumber = sourceVersionNumber; }
    public String getPackageUuid() { return packageUuid; }
    public void setPackageUuid(String packageUuid) { this.packageUuid = packageUuid; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }
    public String getStorageProviderId() { return storageProviderId; }
    public void setStorageProviderId(String storageProviderId) { this.storageProviderId = storageProviderId; }
    public String getStorageBucketName() { return storageBucketName; }
    public void setStorageBucketName(String storageBucketName) { this.storageBucketName = storageBucketName; }
    public String getBasePrefix() { return basePrefix; }
    public void setBasePrefix(String basePrefix) { this.basePrefix = basePrefix; }
    public String getMasterManifestKey() { return masterManifestKey; }
    public void setMasterManifestKey(String masterManifestKey) { this.masterManifestKey = masterManifestKey; }
    public int getHlsVersion() { return hlsVersion; }
    public void setHlsVersion(int hlsVersion) { this.hlsVersion = hlsVersion; }
    public String getSegmentFormat() { return segmentFormat; }
    public void setSegmentFormat(String segmentFormat) { this.segmentFormat = segmentFormat; }
    public int getSegmentDurationSeconds() { return segmentDurationSeconds; }
    public void setSegmentDurationSeconds(int segmentDurationSeconds) { this.segmentDurationSeconds = segmentDurationSeconds; }
    public String getRenditionsJson() { return renditionsJson; }
    public void setRenditionsJson(String renditionsJson) { this.renditionsJson = renditionsJson; }
    public String getMasterManifestSha256() { return masterManifestSha256; }
    public void setMasterManifestSha256(String masterManifestSha256) { this.masterManifestSha256 = masterManifestSha256; }
    public Long getTotalSizeBytes() { return totalSizeBytes; }
    public void setTotalSizeBytes(Long totalSizeBytes) { this.totalSizeBytes = totalSizeBytes; }
    public Integer getObjectCount() { return objectCount; }
    public void setObjectCount(Integer objectCount) { this.objectCount = objectCount; }
    public MediaVideoHlsPackageStatus getStatus() { return status; }
    public void setStatus(MediaVideoHlsPackageStatus status) { this.status = status; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public Instant getReadyAt() { return readyAt; }
    public void setReadyAt(Instant readyAt) { this.readyAt = readyAt; }
    public Instant getSupersededAt() { return supersededAt; }
    public void setSupersededAt(Instant supersededAt) { this.supersededAt = supersededAt; }
    public Instant getPurgeAfter() { return purgeAfter; }
    public void setPurgeAfter(Instant purgeAfter) { this.purgeAfter = purgeAfter; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getPersistenceVersion() { return persistenceVersion; }
    public void setPersistenceVersion(Long persistenceVersion) { this.persistenceVersion = persistenceVersion; }
}
