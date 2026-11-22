package com.universe.media.application.hls;

import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable fenced purge-claim lease issued only after the claiming database transaction
 * commits {@code status = PURGING} and captures the bumped persisted {@code persistence_version}
 * (MS-08 HLS-2B3A2B).
 *
 * <p><strong>Cross-JVM Safety Boundary:</strong> Holding this lease fences database purge
 * completion and failure transitions against stale sweepers, but does NOT itself revoke
 * already-dispatched remote R2 DELETE or delayed PUT requests across separate JVMs.
 */
public record ClaimedHlsPackagePurgeLease(
        UUID packageId,
        UUID assetId,
        UUID sourceVersionId,
        int sourceVersionNumber,
        UUID packageUuid,
        StorageProviderId storageProviderId,
        String storageBucketName,
        StorageKey basePrefix,
        long claimVersion,
        Instant claimExpiresAt
) {

    public ClaimedHlsPackagePurgeLease {
        Objects.requireNonNull(packageId, "packageId cannot be null.");
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId cannot be null.");
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("sourceVersionNumber must be >= 1: " + sourceVersionNumber);
        }
        Objects.requireNonNull(packageUuid, "packageUuid cannot be null.");
        Objects.requireNonNull(storageProviderId, "storageProviderId cannot be null.");
        if (storageBucketName == null || storageBucketName.isBlank()) {
            throw new IllegalArgumentException("storageBucketName cannot be blank.");
        }
        storageBucketName = storageBucketName.trim();
        Objects.requireNonNull(basePrefix, "basePrefix cannot be null.");
        if (claimVersion < 0L) {
            throw new IllegalArgumentException("claimVersion must be >= 0: " + claimVersion);
        }
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt cannot be null.");
    }
}
