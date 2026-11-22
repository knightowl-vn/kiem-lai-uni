package com.universe.media.application.hls;

import com.universe.media.domain.StorageProviderId;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable command instructing {@link HlsLocalPackagingPort} to locally transcode, verify,
 * and inventory one verified source MP4 file under the claimed attempt's identity and bounds.
 */
public record PrepareLocalHlsPackageCommand(
        Path sourceFile,
        UUID assetId,
        int sourceVersionNumber,
        UUID packageUuid,
        StorageProviderId outputProviderId,
        int segmentDurationSeconds,
        int maxObjectCount,
        long maxSingleObjectBytes
) {

    public PrepareLocalHlsPackageCommand {
        Objects.requireNonNull(sourceFile, "sourceFile cannot be null.");
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("sourceVersionNumber must be >= 1, got: " + sourceVersionNumber);
        }
        Objects.requireNonNull(packageUuid, "packageUuid cannot be null.");
        Objects.requireNonNull(outputProviderId, "outputProviderId cannot be null.");
        ClaimMediaVideoPackagingJobUseCase.validateSegmentDurationSeconds(segmentDurationSeconds);
        if (maxObjectCount < 4) {
            throw new IllegalArgumentException("maxObjectCount must be >= 4, got: " + maxObjectCount);
        }
        if (maxSingleObjectBytes <= 0L) {
            throw new IllegalArgumentException("maxSingleObjectBytes must be > 0, got: " + maxSingleObjectBytes);
        }
    }
}
