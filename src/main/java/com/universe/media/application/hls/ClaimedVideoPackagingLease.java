package com.universe.media.application.hls;

import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedVideoPackagingLease(
        UUID jobId,
        UUID assetId,
        UUID sourceVersionId,
        int sourceVersionNumber,
        UUID packageId,
        UUID packageUuid,
        long leaseGeneration,
        int attemptNumber,
        StorageProviderId storageProviderId,
        String storageBucketName,
        StorageKey basePrefix,
        StorageKey masterManifestKey,
        Instant leaseExpiresAt,
        int segmentDurationSeconds
) {

    public ClaimedVideoPackagingLease {
        Objects.requireNonNull(jobId, "jobId cannot be null.");
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId cannot be null.");
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("sourceVersionNumber must be >= 1, got: " + sourceVersionNumber);
        }
        Objects.requireNonNull(packageId, "packageId cannot be null.");
        Objects.requireNonNull(packageUuid, "packageUuid cannot be null.");
        if (leaseGeneration < 1L) {
            throw new IllegalArgumentException("leaseGeneration must be >= 1, got: " + leaseGeneration);
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1, got: " + attemptNumber);
        }
        Objects.requireNonNull(storageProviderId, "storageProviderId cannot be null.");
        if (storageBucketName == null || storageBucketName.isBlank()) {
            throw new IllegalArgumentException("storageBucketName cannot be blank.");
        }
        Objects.requireNonNull(basePrefix, "basePrefix cannot be null.");
        Objects.requireNonNull(masterManifestKey, "masterManifestKey cannot be null.");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt cannot be null.");
        if (segmentDurationSeconds < MediaVideoHlsPackage.MIN_SEGMENT_DURATION_SECONDS
                || segmentDurationSeconds > MediaVideoHlsPackage.MAX_SEGMENT_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    "segmentDurationSeconds must be between "
                            + MediaVideoHlsPackage.MIN_SEGMENT_DURATION_SECONDS + " and "
                            + MediaVideoHlsPackage.MAX_SEGMENT_DURATION_SECONDS + ", got: " + segmentDurationSeconds
            );
        }
    }

    /**
     * Backward-compatible constructor defaulting {@code segmentDurationSeconds} to 6 seconds.
     */
    public ClaimedVideoPackagingLease(
            UUID jobId,
            UUID assetId,
            UUID sourceVersionId,
            int sourceVersionNumber,
            UUID packageId,
            UUID packageUuid,
            long leaseGeneration,
            int attemptNumber,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey basePrefix,
            StorageKey masterManifestKey,
            Instant leaseExpiresAt
    ) {
        this(
                jobId,
                assetId,
                sourceVersionId,
                sourceVersionNumber,
                packageId,
                packageUuid,
                leaseGeneration,
                attemptNumber,
                storageProviderId,
                storageBucketName,
                basePrefix,
                masterManifestKey,
                leaseExpiresAt,
                ClaimMediaVideoPackagingJobUseCase.DEFAULT_SEGMENT_DURATION_SECONDS
        );
    }
}
