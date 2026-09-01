package com.universe.media.application.asset;

import com.universe.media.domain.MediaVisibility;

import java.util.UUID;

public record ChangeMediaVisibilityCommand(
        UUID assetId,
        MediaVisibility newVisibility
) {
}
