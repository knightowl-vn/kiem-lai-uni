package com.universe.screen.application.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Screen-owned snapshot of a video upload session.
 */
public record ScreenUploadSessionInfoDTO(
        UUID sessionId,
        String status,
        String initiatorPrincipalId,
        String consumerContext,
        String targetEntityType,
        UUID targetEntityId,
        UUID registeredAssetId,
        String originalFilename,
        long declaredSizeBytes,
        String clientTag,
        Instant createdAt,
        Instant completedAt
) {
    public ScreenUploadSessionInfoDTO {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(initiatorPrincipalId, "initiatorPrincipalId cannot be null.");
        Objects.requireNonNull(consumerContext, "consumerContext cannot be null.");
        Objects.requireNonNull(targetEntityType, "targetEntityType cannot be null.");
        Objects.requireNonNull(targetEntityId, "targetEntityId cannot be null.");
    }

    /**
     * Backwards-compatible 7-parameter constructor omitting display metadata.
     */
    public ScreenUploadSessionInfoDTO(
            UUID sessionId,
            String status,
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            UUID registeredAssetId
    ) {
        this(
                sessionId,
                status,
                initiatorPrincipalId,
                consumerContext,
                targetEntityType,
                targetEntityId,
                registeredAssetId,
                null,
                0L,
                null,
                null,
                null
        );
    }
}
