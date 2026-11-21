package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaVideoPackagingJobStatus;
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
@Table(name = "media_video_packaging_jobs")
public class MediaVideoPackagingJobJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "asset_id", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String assetId;

    @Column(name = "source_version_id", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String sourceVersionId;

    @Column(name = "source_version_number", nullable = false, updatable = false)
    private int sourceVersionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MediaVideoPackagingJobStatus status;

    @Column(name = "lease_generation", nullable = false)
    private long leaseGeneration;

    @Column(name = "current_package_id", length = 36, columnDefinition = "CHAR(36)")
    private String currentPackageId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "persistence_version", nullable = false)
    private Long persistenceVersion;

    @Transient
    private boolean isNew = true;

    public MediaVideoPackagingJobJpaEntity() {
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
    public MediaVideoPackagingJobStatus getStatus() { return status; }
    public void setStatus(MediaVideoPackagingJobStatus status) { this.status = status; }
    public long getLeaseGeneration() { return leaseGeneration; }
    public void setLeaseGeneration(long leaseGeneration) { this.leaseGeneration = leaseGeneration; }
    public String getCurrentPackageId() { return currentPackageId; }
    public void setCurrentPackageId(String currentPackageId) { this.currentPackageId = currentPackageId; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(Instant heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getPersistenceVersion() { return persistenceVersion; }
    public void setPersistenceVersion(Long persistenceVersion) { this.persistenceVersion = persistenceVersion; }
}
