package com.universe.media.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a fenced async HLS packaging job bound to an exact
 * immutable source {@link MediaAssetVersion}.
 */
public final class MediaVideoPackagingJob {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final UUID id;
    private final UUID assetId;
    private final UUID sourceVersionId;
    private final int sourceVersionNumber;
    private final Instant createdAt;

    private MediaVideoPackagingJobStatus status;
    private long leaseGeneration;
    private UUID currentPackageId;
    private int attemptCount;
    private final int maxAttempts;
    private String leaseOwner;
    private Instant leaseExpiresAt;
    private Instant heartbeatAt;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant updatedAt;
    private Instant completedAt;
    private Long persistenceVersion;

    private MediaVideoPackagingJob(
            UUID id,
            UUID assetId,
            UUID sourceVersionId,
            int sourceVersionNumber,
            MediaVideoPackagingJobStatus status,
            long leaseGeneration,
            UUID currentPackageId,
            int attemptCount,
            int maxAttempts,
            String leaseOwner,
            Instant leaseExpiresAt,
            Instant heartbeatAt,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Long persistenceVersion
    ) {
        this.id = Objects.requireNonNull(id, "Job ID cannot be null.");
        this.assetId = Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        this.sourceVersionId = Objects.requireNonNull(sourceVersionId, "Source version ID cannot be null.");
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("Source version number must be >= 1: " + sourceVersionNumber);
        }
        this.sourceVersionNumber = sourceVersionNumber;
        this.status = Objects.requireNonNull(status, "Job status cannot be null.");
        if (leaseGeneration < 0) {
            throw new IllegalArgumentException("leaseGeneration cannot be negative: " + leaseGeneration);
        }
        this.leaseGeneration = leaseGeneration;
        this.currentPackageId = currentPackageId;
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount cannot be negative: " + attemptCount);
        }
        this.attemptCount = attemptCount;
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.heartbeatAt = heartbeatAt;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt cannot be null.");
        this.lastError = truncateError(lastError);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot precede createdAt.");
        }
        this.completedAt = completedAt;
        this.persistenceVersion = persistenceVersion;
    }

    public static MediaVideoPackagingJob createPending(
            UUID id,
            UUID assetId,
            UUID sourceVersionId,
            int sourceVersionNumber,
            int maxAttempts,
            Instant now
    ) {
        return new MediaVideoPackagingJob(
                id,
                assetId,
                sourceVersionId,
                sourceVersionNumber,
                MediaVideoPackagingJobStatus.PENDING,
                0L,
                null,
                0,
                maxAttempts,
                null,
                null,
                null,
                now,
                null,
                now,
                now,
                null,
                null
        );
    }

    public static MediaVideoPackagingJob rehydrate(
            UUID id,
            UUID assetId,
            UUID sourceVersionId,
            int sourceVersionNumber,
            MediaVideoPackagingJobStatus status,
            long leaseGeneration,
            UUID currentPackageId,
            int attemptCount,
            int maxAttempts,
            String leaseOwner,
            Instant leaseExpiresAt,
            Instant heartbeatAt,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Long persistenceVersion
    ) {
        return new MediaVideoPackagingJob(
                id,
                assetId,
                sourceVersionId,
                sourceVersionNumber,
                status,
                leaseGeneration,
                currentPackageId,
                attemptCount,
                maxAttempts,
                leaseOwner,
                leaseExpiresAt,
                heartbeatAt,
                nextAttemptAt,
                lastError,
                createdAt,
                updatedAt,
                completedAt,
                persistenceVersion
        );
    }

    public boolean isClaimableAt(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status == MediaVideoPackagingJobStatus.PENDING) {
            return !this.nextAttemptAt.isAfter(now);
        }
        if (this.status == MediaVideoPackagingJobStatus.LEASED) {
            return this.leaseExpiresAt == null || !this.leaseExpiresAt.isAfter(now);
        }
        return false;
    }

    /**
     * Returns true iff this job is currently LEASED and its lease has not reached or passed expiration ({@code leaseExpiresAt > now}).
     */
    public boolean isLeaseActiveAt(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return this.status == MediaVideoPackagingJobStatus.LEASED
                && this.leaseExpiresAt != null
                && this.leaseExpiresAt.isAfter(now);
    }

    public boolean hasExhaustedAttempts() {
        return this.attemptCount >= this.maxAttempts;
    }

    /**
     * Claims a PENDING job or reclaims an expired LEASED job, incrementing fencing generation and attempt count.
     */
    public long claimOrReclaimLease(
            String workerId,
            UUID attemptPackageId,
            Duration leaseDuration,
            Instant now
    ) {
        Objects.requireNonNull(workerId, "workerId cannot be null.");
        if (workerId.isBlank() || workerId.trim().length() > 100) {
            throw new IllegalArgumentException("workerId must be 1..100 characters.");
        }
        Objects.requireNonNull(attemptPackageId, "attemptPackageId cannot be null.");
        Objects.requireNonNull(leaseDuration, "leaseDuration cannot be null.");
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be strictly positive.");
        }
        Objects.requireNonNull(now, "Timestamp cannot be null.");

        if (!isClaimableAt(now)) {
            throw new IllegalStateException("Packaging job [" + id + "] is not claimable in state " + status);
        }
        if (hasExhaustedAttempts()) {
            throw new IllegalStateException("Packaging job [" + id + "] has exhausted maxAttempts (" + maxAttempts + ").");
        }

        this.leaseGeneration += 1L;
        this.attemptCount += 1;
        this.status = MediaVideoPackagingJobStatus.LEASED;
        this.leaseOwner = workerId.trim();
        this.leaseExpiresAt = now.plus(leaseDuration);
        this.heartbeatAt = now;
        this.currentPackageId = attemptPackageId;
        touch(now);
        return this.leaseGeneration;
    }

    /**
     * Exhausts a job permanently when an expired lease cannot be retried further.
     */
    public void markExhaustedFailure(String error, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (!hasExhaustedAttempts()) {
            throw new IllegalStateException("Cannot mark job exhausted before reaching maxAttempts.");
        }
        if (this.status != MediaVideoPackagingJobStatus.LEASED && this.status != MediaVideoPackagingJobStatus.PENDING) {
            throw new IllegalStateException("Only PENDING or LEASED job can transition to FAILED, was: " + status);
        }
        this.status = MediaVideoPackagingJobStatus.FAILED;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.lastError = truncateError(error != null && !error.isBlank() ? error : "Exhausted max packaging attempts.");
        touch(now);
    }

    /**
     * Records a failure for the active fenced lease, scheduling retry if attempts remain or transitioning to FAILED.
     */
    public void recordAttemptFailure(
            String workerId,
            long expectedLeaseGeneration,
            String error,
            Duration retryBackoff,
            Instant now
    ) {
        assertValidActiveLease(workerId, expectedLeaseGeneration, now);
        Objects.requireNonNull(retryBackoff, "retryBackoff cannot be null.");

        this.lastError = truncateError(error != null && !error.isBlank() ? error : "Packaging attempt failed.");
        this.leaseOwner = null;
        this.leaseExpiresAt = null;

        if (this.attemptCount < this.maxAttempts) {
            this.status = MediaVideoPackagingJobStatus.PENDING;
            this.nextAttemptAt = now.plus(retryBackoff);
        } else {
            this.status = MediaVideoPackagingJobStatus.FAILED;
        }
        touch(now);
    }

    /**
     * Completes a fenced LEASED job when its package has been verified READY.
     */
    public void markSucceeded(
            String workerId,
            long expectedLeaseGeneration,
            UUID expectedPackageId,
            Instant now
    ) {
        assertValidActiveLease(workerId, expectedLeaseGeneration, now);
        Objects.requireNonNull(expectedPackageId, "expectedPackageId cannot be null.");
        if (!expectedPackageId.equals(this.currentPackageId)) {
            throw new IllegalStateException("Package mismatch completing job [" + id + "].");
        }

        this.status = MediaVideoPackagingJobStatus.SUCCEEDED;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.lastError = null;
        this.completedAt = now;
        touch(now);
    }

    public void cancel(String reason, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status == MediaVideoPackagingJobStatus.SUCCEEDED || this.status == MediaVideoPackagingJobStatus.CANCELLED) {
            return;
        }
        this.status = MediaVideoPackagingJobStatus.CANCELLED;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        if (reason != null && !reason.isBlank()) {
            this.lastError = truncateError(reason);
        }
        touch(now);
    }

    private void assertValidActiveLease(String workerId, long expectedLeaseGeneration, Instant now) {
        assertValidFencedOwner(workerId, expectedLeaseGeneration);
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (!isLeaseActiveAt(now)) {
            throw new IllegalStateException(
                    "Lease expired for job [" + id + "] (leaseExpiresAt=" + this.leaseExpiresAt + ", now=" + now + ")."
            );
        }
    }

    private void assertValidFencedOwner(String workerId, long expectedLeaseGeneration) {
        if (this.status != MediaVideoPackagingJobStatus.LEASED) {
            throw new IllegalStateException("Job [" + id + "] is not LEASED (status=" + this.status + ").");
        }
        if (this.leaseGeneration != expectedLeaseGeneration) {
            throw new IllegalStateException(
                    "Stale lease generation for job [" + id + "]: expected " + expectedLeaseGeneration + ", actual " + this.leaseGeneration
            );
        }
        if (workerId == null || !workerId.trim().equals(this.leaseOwner)) {
            throw new IllegalStateException("Worker [" + workerId + "] does not own lease on job [" + id + "].");
        }
    }

    private void touch(Instant now) {
        if (now.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Timestamp cannot precede createdAt.");
        }
        this.updatedAt = now;
    }

    private static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        String trimmed = error.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public UUID getSourceVersionId() { return sourceVersionId; }
    public int getSourceVersionNumber() { return sourceVersionNumber; }
    public MediaVideoPackagingJobStatus getStatus() { return status; }
    public long getLeaseGeneration() { return leaseGeneration; }
    public UUID getCurrentPackageId() { return currentPackageId; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getPersistenceVersion() { return persistenceVersion; }
}
