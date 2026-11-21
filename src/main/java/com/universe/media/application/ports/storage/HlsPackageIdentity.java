package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.util.Objects;
import java.util.UUID;

/**
 * Validated immutable HLS package storage identity aligned with HLS-1 attempt isolation.
 */
public record HlsPackageIdentity(
        UUID assetId,
        int sourceVersionNumber,
        UUID packageUuid,
        StorageProviderId providerId,
        StorageKey basePrefix,
        StorageKey masterManifestKey
) {

    public HlsPackageIdentity {
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("sourceVersionNumber must be >= 1, got: " + sourceVersionNumber);
        }
        Objects.requireNonNull(packageUuid, "packageUuid cannot be null.");
        HlsPackageKeyValidator.validateRequiredProvider(providerId);
        HlsPackageKeyValidator.ParsedCanonicalPrefix parsed =
                HlsPackageKeyValidator.validatePrefixMatchesIdentity(assetId, sourceVersionNumber, packageUuid, basePrefix);
        Objects.requireNonNull(masterManifestKey, "masterManifestKey cannot be null.");
        if (!parsed.masterManifestKey().equals(masterManifestKey)) {
            throw new IllegalArgumentException(
                    "masterManifestKey mismatch: expected='" + parsed.masterManifestKey().value()
                            + "', got='" + masterManifestKey.value() + "'."
            );
        }
    }

    public static HlsPackageIdentity ofCanonical(
            UUID assetId,
            int sourceVersionNumber,
            UUID packageUuid,
            StorageProviderId providerId
    ) {
        StorageKey basePrefix = HlsPackageKeyValidator.buildCanonicalPrefix(assetId, sourceVersionNumber, packageUuid);
        StorageKey masterKey = HlsPackageKeyValidator.buildAndValidateObjectKey(basePrefix, "master.m3u8");
        return new HlsPackageIdentity(assetId, sourceVersionNumber, packageUuid, providerId, basePrefix, masterKey);
    }
}
