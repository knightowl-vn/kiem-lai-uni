package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Passive result record for a narration media cleanup request (MS-04.9H.8D2A).
 */
public record RequestNarrationMediaCleanupResult(
        UUID mediaAssetId,
        NarrationMediaCleanupOutcome outcome
) {
    public RequestNarrationMediaCleanupResult {
        Objects.requireNonNull(mediaAssetId, "mediaAssetId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
