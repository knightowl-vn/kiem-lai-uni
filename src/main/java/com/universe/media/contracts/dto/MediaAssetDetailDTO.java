package com.universe.media.contracts.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO representing detailed information of a media asset including its current version snapshot.
 */
public record MediaAssetDetailDTO(
        UUID id,
        MediaTypeDTO mediaType,
        MediaVisibilityDTO visibility,
        MediaAssetStatusDTO status,
        int currentVersionNumber,
        Instant createdAt,
        Instant updatedAt,
        MediaVersionDTO currentVersion
) {
}
