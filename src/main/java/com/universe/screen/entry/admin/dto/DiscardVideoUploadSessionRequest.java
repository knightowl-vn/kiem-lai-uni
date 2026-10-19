package com.universe.screen.entry.admin.dto;

import java.util.UUID;

/**
 * Request payload carrying the requested video MediaAsset ID to discard.
 */
public record DiscardVideoUploadSessionRequest(
        UUID videoMediaAssetId
) {
}
