package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.NarrationMediaCleanupReason;

import java.util.UUID;

/**
 * Command record containing parameters for enqueuing a narration media cleanup intent (MS-04.9H.8D1B).
 */
public record EnqueueNarrationMediaCleanupCommand(
        UUID mediaAssetId,
        NarrationMediaCleanupReason reason
) {
}
