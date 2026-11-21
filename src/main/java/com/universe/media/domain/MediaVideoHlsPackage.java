package com.universe.media.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
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

    public void markPurging(Instant now) {
        if (this.status != MediaVideoHlsPackageStatus.FAILED
                && this.status != MediaVideoHlsPackageStatus.ABANDONED
                && this.status != MediaVideoHlsPackageStatus.SUPERSEDED) {
            throw new IllegalStateException("Only terminal non-READY package can enter PURGING, was: " + this.status);
        }
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.status = MediaVideoHlsPackageStatus.PURGING;
        touch(now);
    }

    public void markPurged(Instant now) {
        if (this.status != MediaVideoHlsPackageStatus.PURGING) {
            throw new IllegalStateException("Only PURGING package can transition to PURGED, was: " + this.status);
        }
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.status = MediaVideoHlsPackageStatus.PURGED;
        touch(now);
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
