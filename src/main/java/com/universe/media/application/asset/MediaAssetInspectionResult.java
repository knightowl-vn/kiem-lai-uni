package com.universe.media.application.asset;

import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;

import java.util.UUID;

/**
 * Application-layer result representing the locked inspection state of a media asset.
 */
public record MediaAssetInspectionResult(
        UUID id,
        MediaType mediaType,
        MediaVisibility visibility,
        MediaAssetStatus status,
        int currentVersionNumber
) {
}
