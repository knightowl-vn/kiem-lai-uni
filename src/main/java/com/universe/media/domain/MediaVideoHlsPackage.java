package com.universe.media.domain;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain entity representing one immutable HLS fMP4 package attempt bound to an exact
 * source {@link MediaAssetVersion}.
 */
public final class MediaVideoHlsPackage {

    public static final String SEGMENT_FORMAT_FMP4 = "FMP4";
    public static final int DEFAULT_HLS_VERSION = 7;
    public static final int MIN_SEGMENT_DURATION_SECONDS = 2;
    public static final int MAX_SEGMENT_DURATION_SECONDS = 10;
    public static final int MIN_READY_OBJECT_COUNT = 4;

    /**
     * Conservative minimum operational retention floor before FAILED or reclaim-ABANDONED
     * package attempts may become eligible for future cleanup (MS-08 HLS-2B3A2A).
     */
    public static final Duration FAILED_RETENTION_GRACE = Duration.ofHours(24);
    public static final Duration ABANDONED_ON_RECLAIM_RETENTION_GRACE = Duration.ofHours(24);
    public static final Duration SUPERSEDED_RETENTION_GRACE = Duration.ofDays(7);
    /**
     * Minimum claim duration (1 second = 1,000,000 microseconds) preserves microsecond precision in
     * MySQL DATETIME(6) columns and prevents sub-second micro-leases that would expire mid-handoff.
     */
    public static final Duration MIN_PURGE_CLAIM_LEASE = Duration.ofSeconds(1);
    public static final Duration DEFAULT_PURGE_CLAIM_LEASE = Duration.ofMinutes(5);
    /**
     * Maximum single purge claim duration (30 minutes), aligned with R2 prefix purge operation budgets
     * (~60s-180s per 5,000 keys with retries) while capping orphan PURGING crash recovery windows.
     */
    public static final Duration MAX_PURGE_CLAIM_LEASE = Duration.ofMinutes(30);
    public static final Duration MAX_PURGE_RETRY_BACKOFF = Duration.ofHours(24);
    private static final String PURGE_ERROR_MARKER = " | [purge-error: ";

    private final UUID id;
    private final UUID assetId;
    private final UUID sourceVersionId;
    private final int sourceVersionNumber;
    private final UUID packageUuid;
    private final int attemptNumber;
    private final StorageProviderId storageProviderId;
    private final String storageBucketName;
    private final StorageKey basePrefix;
    private final StorageKey masterManifestKey;
    private final int hlsVersion;
    private final String segmentFormat;
    private final int segmentDurationSeconds;
    private final Instant createdAt;

    private String renditionsJson;
    private ContentHash masterManifestSha256;
    private Long totalSizeBytes;
    private Integer objectCount;
    private MediaVideoHlsPackageStatus status;
    private String failureReason;
    private Instant verifiedAt;
    private Instant readyAt;
    private Instant supersededAt;
    private Instant purgeAfter;
    private Instant updatedAt;
    private Long persistenceVersion;

    private MediaVideoHlsPackage(
            UUID id,
            UUID assetId,
            UUID sourceVersionId,
            int sourceVersionNumber,
            UUID packageUuid,
            int attemptNumber,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey basePrefix,
            StorageKey masterManifestKey,
            int hlsVersion,
            String segmentFormat,
            int segmentDurationSeconds,
            String renditionsJson,
            ContentHash masterManifestSha256,
            Long totalSizeBytes,
            Integer objectCount,
            MediaVideoHlsPackageStatus status,
            String failureReason,
            Instant createdAt,
            Instant verifiedAt,
            Instant readyAt,
            Instant supersededAt,
            Instant purgeAfter,
            Instant updatedAt,
            Long persistenceVersion
    ) {
        this.id = Objects.requireNonNull(id, "Package ID cannot be null.");
        this.assetId = Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        this.sourceVersionId = Objects.requireNonNull(sourceVersionId, "Source version ID cannot be null.");
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("Source version number must be >= 1: " + sourceVersionNumber);
        }
        this.sourceVersionNumber = sourceVersionNumber;
        this.packageUuid = Objects.requireNonNull(packageUuid, "Package UUID cannot be null.");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be >= 1: " + attemptNumber);
        }
        this.attemptNumber = attemptNumber;
        this.storageProviderId = Objects.requireNonNull(storageProviderId, "StorageProviderId cannot be null.");
        if (storageBucketName == null || storageBucketName.isBlank()) {
            throw new IllegalArgumentException("Storage bucket name cannot be blank.");
        }
        this.storageBucketName = storageBucketName.trim();
        this.basePrefix = Objects.requireNonNull(basePrefix, "Base prefix cannot be null.");
        this.masterManifestKey = Objects.requireNonNull(masterManifestKey, "Master manifest key cannot be null.");
        if (!masterManifestKey.value().startsWith(basePrefix.value())) {
            throw new IllegalArgumentException("Master manifest key must reside inside basePrefix.");
        }
        if (hlsVersion < DEFAULT_HLS_VERSION) {
            throw new IllegalArgumentException("HLS version for fMP4 must be >= 7: " + hlsVersion);
        }
        this.hlsVersion = hlsVersion;
        if (!SEGMENT_FORMAT_FMP4.equals(segmentFormat)) {
            throw new IllegalArgumentException("Unsupported segment format: " + segmentFormat);
        }
        this.segmentFormat = segmentFormat;
        if (segmentDurationSeconds < MIN_SEGMENT_DURATION_SECONDS
                || segmentDurationSeconds > MAX_SEGMENT_DURATION_SECONDS) {
            throw new IllegalArgumentException("Segment duration out of bounds (2..10s): " + segmentDurationSeconds);
        }
        this.segmentDurationSeconds = segmentDurationSeconds;
        this.renditionsJson = renditionsJson;
        this.masterManifestSha256 = masterManifestSha256;
        this.totalSizeBytes = totalSizeBytes;
        this.objectCount = objectCount;
        this.status = Objects.requireNonNull(status, "Status cannot be null.");
        this.failureReason = truncateError(failureReason);
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt cannot be null.");
        this.verifiedAt = verifiedAt;
        this.readyAt = readyAt;
        this.supersededAt = supersededAt;
        this.purgeAfter = purgeAfter;
        this.updatedAt = Objects.requireNonNull(updatedAt, "UpdatedAt cannot be null.");
        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt cannot precede createdAt.");
        }
        this.persistenceVersion = persistenceVersion;
    }

    public static MediaVideoHlsPackage startProcessing(
            UUID id,
            UUID assetId,
            UUID sourceVersionId,
            int sourceVersionNumber,
            UUID packageUuid,
            int attemptNumber,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey basePrefix,
            int segmentDurationSeconds,
            Instant now
    ) {
        Objects.requireNonNull(basePrefix, "Base prefix cannot be null.");
        String normalizedPrefix = basePrefix.value().endsWith("/") ? basePrefix.value() : basePrefix.value() + "/";
        StorageKey prefixKey = StorageKey.of(normalizedPrefix);
        StorageKey masterKey = StorageKey.of(normalizedPrefix + "master.m3u8");
        return new MediaVideoHlsPackage(
                id,
                assetId,
                sourceVersionId,
                sourceVersionNumber,
                packageUuid,
                attemptNumber,
                storageProviderId,
                storageBucketName,
                prefixKey,
                masterKey,
                DEFAULT_HLS_VERSION,
                SEGMENT_FORMAT_FMP4,
                segmentDurationSeconds,
                null,
                null,
                null,
                null,
                MediaVideoHlsPackageStatus.PROCESSING,
                null,
                now,
                null,
                null,
                null,
                null,
                now,
                null
        );
    }

    public static MediaVideoHlsPackage rehydrate(
            UUID id,
            UUID assetId,
            UUID sourceVersionId,
            int sourceVersionNumber,
            UUID packageUuid,
            int attemptNumber,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey basePrefix,
            StorageKey masterManifestKey,
            int hlsVersion,
            String segmentFormat,
            int segmentDurationSeconds,
            String renditionsJson,
            ContentHash masterManifestSha256,
            Long totalSizeBytes,
            Integer objectCount,
            MediaVideoHlsPackageStatus status,
            String failureReason,
            Instant createdAt,
            Instant verifiedAt,
            Instant readyAt,
            Instant supersededAt,
            Instant purgeAfter,
            Instant updatedAt,
            Long persistenceVersion
    ) {
        return new MediaVideoHlsPackage(
                id,
                assetId,
                sourceVersionId,
                sourceVersionNumber,
                packageUuid,
                attemptNumber,
                storageProviderId,
                storageBucketName,
                basePrefix,
                masterManifestKey,
                hlsVersion,
                segmentFormat,
                segmentDurationSeconds,
                renditionsJson,
                masterManifestSha256,
                totalSizeBytes,
                objectCount,
                status,
                failureReason,
                createdAt,
                verifiedAt,
                readyAt,
                supersededAt,
                purgeAfter,
                updatedAt,
                persistenceVersion
        );
    }

    public static Instant computeFailedPurgeAfter(Instant failedAt) {
        Objects.requireNonNull(failedAt, "Timestamp cannot be null.");
        return failedAt.plus(FAILED_RETENTION_GRACE);
    }

    public static Instant computeAbandonedOnReclaimPurgeAfter(Instant abandonedAt) {
        Objects.requireNonNull(abandonedAt, "Timestamp cannot be null.");
        return abandonedAt.plus(ABANDONED_ON_RECLAIM_RETENTION_GRACE);
    }

    public static Instant computeSupersededPurgeAfter(Instant supersededAt) {
        Objects.requireNonNull(supersededAt, "Timestamp cannot be null.");
        return supersededAt.plus(SUPERSEDED_RETENTION_GRACE);
    }

    /**
     * Transitions a PROCESSING package to READY after remote artifact verification evidence is supplied.
     */
    public void markReady(
            ContentHash masterManifestSha256,
            long totalSizeBytes,
            int objectCount,
            String renditionsJson,
            Instant verifiedAt,
            Instant now
    ) {
        if (this.status != MediaVideoHlsPackageStatus.PROCESSING) {
            throw new IllegalStateException("Only PROCESSING HLS package can transition to READY, was: " + this.status);
        }
        this.masterManifestSha256 = Objects.requireNonNull(masterManifestSha256, "masterManifestSha256 cannot be null.");
        if (totalSizeBytes <= 0) {
            throw new IllegalArgumentException("totalSizeBytes must be > 0: " + totalSizeBytes);
        }
        if (objectCount < MIN_READY_OBJECT_COUNT) {
            throw new IllegalArgumentException("objectCount must be >= " + MIN_READY_OBJECT_COUNT + ": " + objectCount);
        }
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt cannot be null.");
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.totalSizeBytes = totalSizeBytes;
        this.objectCount = objectCount;
        this.renditionsJson = renditionsJson;
        this.failureReason = null;
        this.status = MediaVideoHlsPackageStatus.READY;
        this.readyAt = now;
        touch(now);
    }

    public void markFailed(String reason, Instant purgeAfter, Instant now) {
        if (this.status != MediaVideoHlsPackageStatus.PROCESSING) {
            throw new IllegalStateException("Only PROCESSING HLS package can transition to FAILED, was: " + this.status);
        }
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.status = MediaVideoHlsPackageStatus.FAILED;
        this.failureReason = truncateError(reason != null && !reason.isBlank() ? reason : "Packaging attempt failed.");
        this.purgeAfter = Objects.requireNonNull(purgeAfter, "purgeAfter cannot be null.");
        touch(now);
    }

    public void markAbandoned(String reason, Instant purgeAfter, Instant now) {
        if (this.status != MediaVideoHlsPackageStatus.PROCESSING) {
            throw new IllegalStateException("Only PROCESSING HLS package can transition to ABANDONED, was: " + this.status);
        }
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.status = MediaVideoHlsPackageStatus.ABANDONED;
        this.failureReason = truncateError(reason);
        this.purgeAfter = Objects.requireNonNull(purgeAfter, "purgeAfter cannot be null.");
        touch(now);
    }

    public void markSuperseded(Instant purgeAfter, Instant now) {
        if (this.status != MediaVideoHlsPackageStatus.READY && this.status != MediaVideoHlsPackageStatus.PROCESSING) {
            throw new IllegalStateException("Cannot supersede HLS package in state: " + this.status);
        }
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.status = MediaVideoHlsPackageStatus.SUPERSEDED;
        this.supersededAt = now;
        this.purgeAfter = Objects.requireNonNull(purgeAfter, "purgeAfter cannot be null.");
        touch(now);
    }

    /**
     * Computes the conservative effective purge eligibility deadline enforced before any package
     * may enter or re-enter {@link MediaVideoHlsPackageStatus#PURGING}, failing closed when required
     * persisted timestamps are missing or inconsistent.
     */
    public Optional<Instant> effectivePurgeEligibleAt() {
        if (this.purgeAfter == null) {
            return Optional.empty();
        }
        return switch (this.status) {
            case PROCESSING, READY, PURGED -> Optional.empty();
            case FAILED, ABANDONED -> {
                if (this.updatedAt.isBefore(this.createdAt)) {
                    yield Optional.empty();
                }
                Instant minFloor = this.updatedAt.plus(FAILED_RETENTION_GRACE);
                yield Optional.of(this.purgeAfter.isAfter(minFloor) ? this.purgeAfter : minFloor);
            }
            case SUPERSEDED -> {
                if (this.supersededAt == null || this.supersededAt.isBefore(this.createdAt)) {
                    yield Optional.empty();
                }
                Instant minFloor = this.supersededAt.plus(SUPERSEDED_RETENTION_GRACE);
                yield Optional.of(this.purgeAfter.isAfter(minFloor) ? this.purgeAfter : minFloor);
            }
            case PURGING -> Optional.of(this.purgeAfter);
        };
    }

    public boolean isPurgeEligibleAt(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return effectivePurgeEligibleAt()
                .map(eligibleAt -> !eligibleAt.isAfter(now))
                .orElse(false);
    }

    /**
     * Returns true iff this package is currently in PURGING and its claim deadline has not expired ({@code purgeAfter > now}).
     */
    public boolean isPurgeClaimActiveAt(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return this.status == MediaVideoHlsPackageStatus.PURGING
                && this.purgeAfter != null
                && this.purgeAfter.isAfter(now);
    }

    /**
     * Validates that a purge claim lease duration is strictly within bounded operational limits
     * [{@link #MIN_PURGE_CLAIM_LEASE}, {@link #MAX_PURGE_CLAIM_LEASE}].
     */
    public static void validatePurgeClaimDuration(Duration claimDuration) {
        Objects.requireNonNull(claimDuration, "claimDuration cannot be null.");
        if (claimDuration.isNegative() || claimDuration.isZero()) {
            throw new IllegalArgumentException("claimDuration must be strictly positive: " + claimDuration);
        }
        if (claimDuration.compareTo(MIN_PURGE_CLAIM_LEASE) < 0) {
            throw new IllegalArgumentException(
                    "claimDuration (" + claimDuration + ") is below minimum DATETIME(6)-safe lease ("
                            + MIN_PURGE_CLAIM_LEASE + ")."
            );
        }
        if (claimDuration.compareTo(MAX_PURGE_CLAIM_LEASE) > 0) {
            throw new IllegalArgumentException(
                    "claimDuration (" + claimDuration + ") exceeds maximum allowed purge claim lease ("
                            + MAX_PURGE_CLAIM_LEASE + ")."
            );
        }
    }

    /**
     * Computes a validated, overflow-safe purge claim expiration instant from the supplied timestamp.
     */
    public static Instant computePurgeClaimExpiresAt(Instant now, Duration claimDuration) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        validatePurgeClaimDuration(claimDuration);
        try {
            return now.plus(claimDuration);
        } catch (ArithmeticException | DateTimeException ex) {
            throw new IllegalArgumentException("Purge claim deadline overflows Instant bounds.", ex);
        }
    }

    /**
     * Claims a retention-eligible FAILED, ABANDONED, or SUPERSEDED package, or reclaims an expired
     * PURGING claim, advancing status to PURGING and setting the claim deadline in {@code purgeAfter}.
     */
    public void claimForPurge(Instant claimExpiresAt, Instant now) {
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt cannot be null.");
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (!claimExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "Purge claimExpiresAt (" + claimExpiresAt + ") must be strictly in the future of now (" + now + ")."
            );
        }
        Duration requestedLease;
        try {
            requestedLease = Duration.between(now, claimExpiresAt);
        } catch (ArithmeticException | DateTimeException ex) {
            throw new IllegalArgumentException("Purge claim duration overflows Instant bounds.", ex);
        }
        validatePurgeClaimDuration(requestedLease);
        if (now.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Timestamp cannot precede createdAt.");
        }
        if (this.status != MediaVideoHlsPackageStatus.FAILED
                && this.status != MediaVideoHlsPackageStatus.ABANDONED
                && this.status != MediaVideoHlsPackageStatus.SUPERSEDED
                && this.status != MediaVideoHlsPackageStatus.PURGING) {
            throw new IllegalStateException("Only terminal non-READY or expired PURGING package can be claimed for purge, was: " + this.status);
        }
        if (!isPurgeEligibleAt(now)) {
            throw new IllegalStateException(
                    "HLS package [" + this.id + "] in status " + this.status
                            + " is not purge-eligible at " + now + " (effectiveEligibleAt="
                            + effectivePurgeEligibleAt().orElse(null) + ")."
            );
        }
        this.status = MediaVideoHlsPackageStatus.PURGING;
        this.purgeAfter = claimExpiresAt;
        touch(now);
    }

    /**
     * Records a purge attempt failure for an active PURGING claim, preserving existing diagnostic failure context
     * while scheduling a bounded future retry timestamp in {@code purgeAfter}.
     */
    public void recordPurgeFailure(String purgeError, Instant nextPurgeAfter, Instant now) {
        Objects.requireNonNull(nextPurgeAfter, "nextPurgeAfter cannot be null.");
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (!isPurgeClaimActiveAt(now)) {
            throw new IllegalStateException(
                    "Cannot record purge failure without an active PURGING claim at " + now
                            + " (status=" + this.status + ", purgeAfter=" + this.purgeAfter + ")."
            );
        }
        if (!nextPurgeAfter.isAfter(now)) {
            throw new IllegalArgumentException(
                    "Purge retry timestamp (" + nextPurgeAfter + ") must be strictly in the future of now (" + now + ")."
            );
        }
        Duration requestedBackoff;
        try {
            requestedBackoff = Duration.between(now, nextPurgeAfter);
        } catch (ArithmeticException | DateTimeException ex) {
            throw new IllegalArgumentException("Purge retry duration overflows Instant bounds.", ex);
        }
        if (requestedBackoff.compareTo(MAX_PURGE_RETRY_BACKOFF) > 0) {
            throw new IllegalArgumentException(
                    "Purge retry backoff (" + requestedBackoff + ") exceeds maximum allowed backoff ("
                            + MAX_PURGE_RETRY_BACKOFF + ")."
            );
        }
        this.failureReason = mergePurgeFailureReason(this.failureReason, purgeError);
        this.purgeAfter = nextPurgeAfter;
        touch(now);
    }

    public void markPurging(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        claimForPurge(computePurgeClaimExpiresAt(now, DEFAULT_PURGE_CLAIM_LEASE), now);
    }

    public void markPurged(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaVideoHlsPackageStatus.PURGING) {
            throw new IllegalStateException("Only PURGING package can transition to PURGED, was: " + this.status);
        }
        if (!isPurgeClaimActiveAt(now)) {
            throw new IllegalStateException(
                    "Purge claim expired before confirmation at " + now + " (purgeAfter=" + this.purgeAfter + ")."
            );
        }
        this.status = MediaVideoHlsPackageStatus.PURGED;
        this.purgeAfter = null;
        touch(now);
    }

    private static String mergePurgeFailureReason(String existingReason, String purgeError) {
        String sanitizedPurgeErr = (purgeError == null || purgeError.isBlank())
                ? "Purge attempt failed."
                : purgeError.trim();
        String base = existingReason;
        if (base != null) {
            int markerIdx = base.indexOf(PURGE_ERROR_MARKER);
            if (markerIdx >= 0) {
                base = base.substring(0, markerIdx).trim();
            } else if (base.startsWith("[purge-error: ")) {
                base = null;
            }
        }
        if (base == null || base.isBlank()) {
            return truncateError("[purge-error: " + sanitizedPurgeErr + "]");
        }
        int reserved = PURGE_ERROR_MARKER.length() + 1;
        String basePrefix = base.length() > (1000 - reserved - 32) ? base.substring(0, 1000 - reserved - 32) : base;
        return truncateError(basePrefix + PURGE_ERROR_MARKER + sanitizedPurgeErr + "]");
    }

    private void touch(Instant now) {
        if (now.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Timestamp cannot precede createdAt.");
        }
        this.updatedAt = now;
    }

    private static String truncateError(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public UUID getSourceVersionId() { return sourceVersionId; }
    public int getSourceVersionNumber() { return sourceVersionNumber; }
    public UUID getPackageUuid() { return packageUuid; }
    public int getAttemptNumber() { return attemptNumber; }
    public StorageProviderId getStorageProviderId() { return storageProviderId; }
    public String getStorageBucketName() { return storageBucketName; }
    public StorageKey getBasePrefix() { return basePrefix; }
    public StorageKey getMasterManifestKey() { return masterManifestKey; }
    public int getHlsVersion() { return hlsVersion; }
    public String getSegmentFormat() { return segmentFormat; }
    public int getSegmentDurationSeconds() { return segmentDurationSeconds; }
    public String getRenditionsJson() { return renditionsJson; }
    public ContentHash getMasterManifestSha256() { return masterManifestSha256; }
    public Long getTotalSizeBytes() { return totalSizeBytes; }
    public Integer getObjectCount() { return objectCount; }
    public MediaVideoHlsPackageStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getSupersededAt() { return supersededAt; }
    public Instant getPurgeAfter() { return purgeAfter; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getPersistenceVersion() { return persistenceVersion; }
}
