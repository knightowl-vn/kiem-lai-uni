package com.universe.media.contracts.dto;

import java.util.UUID;

/**
 * Public consumer-neutral DTO for immutable MediaAssetVersion provenance.
 */
public record MediaAssetVersionSnapshotDTO(
        UUID assetId,
        int versionNumber,
        String contentHash,
        String mimeType,
        long sizeBytes,
        String originalFilename
) {
}
