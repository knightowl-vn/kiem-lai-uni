package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.NarrationMediaCleanupReason;

import java.util.UUID;

/**
 * Passive command record to request cleanup of an unreferenced Media asset (MS-04.9H.8D2A).
 */
public record RequestNarrationMediaCleanupCommand(
        UUID mediaAssetId,
        NarrationMediaCleanupReason reason
) {
}
