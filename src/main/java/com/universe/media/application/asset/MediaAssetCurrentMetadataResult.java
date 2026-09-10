package com.universe.media.application.asset;

import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;

import java.util.UUID;

public record MediaAssetCurrentMetadataResult(
        UUID id,
        MediaAssetStatus status,
        MediaVisibility visibility,
        int currentVersionNumber
) {
}
