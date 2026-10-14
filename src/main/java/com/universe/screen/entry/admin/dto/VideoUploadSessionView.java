package com.universe.screen.entry.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * View DTO describing an initiated multipart upload session for a ScreenEntry.
 */
public record VideoUploadSessionView(
        UUID sessionId,
        String status,
        long declaredSizeBytes,
        long partSizeBytes,
        int expectedPartCount,
        String originalFilename,
        UUID registeredAssetId,
        Instant expiresAt
) {
}
