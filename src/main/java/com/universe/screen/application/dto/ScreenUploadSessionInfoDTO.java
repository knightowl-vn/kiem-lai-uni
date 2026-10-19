package com.universe.screen.application.dto;

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
        UUID registeredAssetId
) {
    public ScreenUploadSessionInfoDTO {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(initiatorPrincipalId, "initiatorPrincipalId cannot be null.");
        Objects.requireNonNull(consumerContext, "consumerContext cannot be null.");
        Objects.requireNonNull(targetEntityType, "targetEntityType cannot be null.");
        Objects.requireNonNull(targetEntityId, "targetEntityId cannot be null.");
    }
}
