package com.universe.screen.application.dto;

import java.util.UUID;

/**
 * Outcome container for the Admin-authorized Screen video visibility reconciliation command.
 */
public record ReconcileScreenVideoVisibilityResult(
        UUID titleId,
        UUID entryId,
        UUID videoMediaAssetId,
        String previousVisibility,
        String reconciledVisibility,
        Outcome outcome,
        String message
) {
    public enum Outcome {
        RECONCILED,
        ALREADY_PUBLIC,
        KEPT_PRIVATE,
        KEPT_PUBLIC_BY_SHARED_REFERENCE,
        NO_VIDEO_ATTACHED
    }
}
