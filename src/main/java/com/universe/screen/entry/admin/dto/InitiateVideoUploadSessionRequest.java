package com.universe.screen.entry.admin.dto;

import java.util.Objects;

/**
 * Request payload to initiate a multipart video upload session for a ScreenEntry.
 */
public record InitiateVideoUploadSessionRequest(
        long declaredSizeBytes,
        String originalFilename,
        String idempotencyKey
) {
    public InitiateVideoUploadSessionRequest {
        if (declaredSizeBytes <= 0) {
            throw new IllegalArgumentException("declaredSizeBytes must be strictly positive.");
        }
        Objects.requireNonNull(originalFilename, "originalFilename cannot be null.");
        if (originalFilename.isBlank()) {
            throw new IllegalArgumentException("originalFilename cannot be blank.");
        }
    }
}
