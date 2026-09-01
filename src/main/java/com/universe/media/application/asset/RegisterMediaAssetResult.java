package com.universe.media.application.asset;

import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;

import java.time.Instant;
import java.util.UUID;

public record RegisterMediaAssetResult(
        UUID assetId,
        UUID versionId,
        int versionNumber,
        MediaType mediaType,
        MediaVisibility visibility,
        MediaAssetStatus status,
        Instant createdAt
) {
}
