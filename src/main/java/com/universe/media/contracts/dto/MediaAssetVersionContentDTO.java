package com.universe.media.contracts.dto;

import java.io.InputStream;
import java.util.UUID;

/**
 * Public consumer-neutral DTO containing exact immutable MediaAssetVersion content.
 *
 * <p><strong>Stream Ownership:</strong> The caller owns and must close the returned {@link InputStream}.
 */
public record MediaAssetVersionContentDTO(
        UUID assetId,
        int versionNumber,
        String contentHash,
        String mimeType,
        long sizeBytes,
        InputStream content
) {
}
