package com.universe.screen.entry.admin.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Safe, sanitized response view returned after discarding an unattached video upload.
 */
public record DiscardVideoUploadResponse(
        UUID sessionId,
        UUID videoMediaAssetId,
        String status,
        String message
) {
    public DiscardVideoUploadResponse {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(videoMediaAssetId, "videoMediaAssetId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(message, "message cannot be null.");
    }
}
