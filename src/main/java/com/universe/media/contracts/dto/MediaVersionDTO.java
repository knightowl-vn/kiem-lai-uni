package com.universe.media.contracts.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO representing consumer-relevant metadata of an immutable media version snapshot.
 */
public record MediaVersionDTO(
        UUID id,
        UUID assetId,
        int versionNumber,
        String publicUrl,
        String mimeType,
        long sizeBytes,
        String originalFilename,
        Instant createdAt
) {
}
