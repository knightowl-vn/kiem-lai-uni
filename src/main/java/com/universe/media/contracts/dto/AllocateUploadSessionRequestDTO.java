package com.universe.media.contracts.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Request DTO for allocating a durable multipart upload session for large video files.
 */
public record AllocateUploadSessionRequestDTO(
        String initiatorPrincipalId,
        String consumerContext,
        String targetEntityType,
        UUID targetEntityId,
        String idempotencyKey,
        long declaredSizeBytes,
        String originalFilename,
        String clientTag
) {
    public AllocateUploadSessionRequestDTO {
        Objects.requireNonNull(initiatorPrincipalId, "initiatorPrincipalId cannot be null.");
        Objects.requireNonNull(consumerContext, "consumerContext cannot be null.");
        Objects.requireNonNull(targetEntityType, "targetEntityType cannot be null.");
        Objects.requireNonNull(targetEntityId, "targetEntityId cannot be null.");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null.");
        Objects.requireNonNull(originalFilename, "originalFilename cannot be null.");
    }
}
