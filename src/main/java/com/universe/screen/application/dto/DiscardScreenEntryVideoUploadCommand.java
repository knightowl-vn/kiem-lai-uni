package com.universe.screen.application.dto;

import java.util.UUID;

/**
 * Command carrying parameters for guarded discard of a completed video upload.
 */
public record DiscardScreenEntryVideoUploadCommand(
        UUID titleId,
        UUID entryId,
        UUID sessionId,
        UUID videoMediaAssetId,
        String authenticatedActor
) {
}
