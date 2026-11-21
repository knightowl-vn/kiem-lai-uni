package com.universe.media.application.hls;

import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ContentHashStatus;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable validated metadata snapshot identifying the exact source MP4 version
 * bound to an HLS packaging attempt (MS-08 HLS2B3A1).
 *
 * <p><strong>Fencing Notice:</strong> Obtaining this snapshot proves only that the
 * source version was authoritative during the short read-only lookup transaction.
 * It does NOT replace HLS-1 lease and source-version fencing at {@code READY}
 * completion via {@link CompleteVerifiedHlsPackagingUseCase}.
 */
public record MediaVideoSourceSnapshot(
        UUID assetId,
        UUID sourceVersionId,
        int sourceVersionNumber,
        StorageProviderId storageProviderId,
        StorageKey storageKey,
        ContentHash expectedContentHash,
        ContentHashStatus contentHashStatus,
        long expectedSizeBytes,
        MimeType mimeType
) {

    public MediaVideoSourceSnapshot {
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId cannot be null.");
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("sourceVersionNumber must be >= 1, got: " + sourceVersionNumber);
        }
        Objects.requireNonNull(storageProviderId, "storageProviderId cannot be null.");
        Objects.requireNonNull(storageKey, "storageKey cannot be null.");
        Objects.requireNonNull(expectedContentHash, "expectedContentHash cannot be null.");
        if (expectedSizeBytes <= 0L) {
            throw new IllegalArgumentException("expectedSizeBytes must be > 0, got: " + expectedSizeBytes);
        }
        Objects.requireNonNull(mimeType, "mimeType cannot be null.");
    }
}
