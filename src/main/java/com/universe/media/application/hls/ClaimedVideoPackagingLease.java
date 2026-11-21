package com.universe.media.application.hls;

import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.time.Instant;
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
        Instant leaseExpiresAt
) {
}
