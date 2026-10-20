package com.universe.media.contracts.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public response DTO exposing state of a Media multipart upload session.
 */
public record MediaUploadSessionResponseDTO(
        UUID sessionId,
        String status,
        String initiatorPrincipalId,
        String consumerContext,
        String targetEntityType,
        UUID targetEntityId,
        long declaredSizeBytes,
        long partSizeBytes,
        int expectedPartCount,
        String originalFilename,
        String clientTag,
        UUID registeredAssetId,
        Instant createdAt,
        Instant expiresAt,
        Instant completedAt
) {
    public MediaUploadSessionResponseDTO {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
        Objects.requireNonNull(initiatorPrincipalId, "initiatorPrincipalId cannot be null.");
        Objects.requireNonNull(consumerContext, "consumerContext cannot be null.");
        Objects.requireNonNull(targetEntityType, "targetEntityType cannot be null.");
        Objects.requireNonNull(targetEntityId, "targetEntityId cannot be null.");
        Objects.requireNonNull(originalFilename, "originalFilename cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null.");
    }

    /**
     * Backwards-compatible 14-parameter constructor omitting completedAt.
     */
    public MediaUploadSessionResponseDTO(
            UUID sessionId,
            String status,
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            long declaredSizeBytes,
            long partSizeBytes,
            int expectedPartCount,
            String originalFilename,
            String clientTag,
            UUID registeredAssetId,
            Instant createdAt,
            Instant expiresAt
    ) {
        this(
                sessionId,
                status,
                initiatorPrincipalId,
                consumerContext,
                targetEntityType,
                targetEntityId,
                declaredSizeBytes,
                partSizeBytes,
                expectedPartCount,
                originalFilename,
                clientTag,
                registeredAssetId,
                createdAt,
                expiresAt,
                null
        );
    }
}
