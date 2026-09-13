package com.universe.media.application.asset;

import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;

import java.time.Instant;
import java.util.UUID;

public record MediaAssetDetailResult(
        UUID id,
        MediaType mediaType,
        MediaVisibility visibility,
        MediaAssetStatus status,
        int currentVersionNumber,
        Instant createdAt,
        Instant updatedAt,
        MediaVersionItemResult currentVersion
) {
}
