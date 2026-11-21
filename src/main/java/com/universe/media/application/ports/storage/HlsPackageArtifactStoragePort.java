package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

/**
 * Dedicated storage boundary for HLS package artifact operations and validated prefix cleanup.
 * Intentionally separated from {@link BinaryStoragePort} to avoid broad unrestricted prefix deletes.
 * Concrete provider implementations belong to HLS-2.
 */
public interface HlsPackageArtifactStoragePort {

    /**
     * Purges all HLS package objects under a validated immutable package prefix
     * (e.g. {@code objects/hls/<assetId>/v<ver>/<pkgUuid>/}).
     */
    void purgePackagePrefix(StorageProviderId providerId, String bucketName, StorageKey validatedBasePrefix);
}
