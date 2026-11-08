package com.universe.screen.entry.admin.dto;

import java.util.UUID;

/**
 * REST response DTO for the Admin-authorized video visibility reconciliation endpoint.
 */
public record ReconcileScreenVideoVisibilityResponse(
        UUID titleId,
        UUID entryId,
        UUID videoMediaAssetId,
        String reconciledVisibility,
        String outcome,
        String message
) {
}
