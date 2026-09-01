package com.universe.media.contracts.dto;

import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;

import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO representing detailed information of a media asset including its current version snapshot.
 */
public record MediaAssetDetailDTO(
        UUID id,
        MediaType mediaType,
        MediaVisibility visibility,
        MediaAssetStatus status,
        int currentVersionNumber,
        Instant createdAt,
        Instant updatedAt,
        MediaVersionDTO currentVersion
) {
}
