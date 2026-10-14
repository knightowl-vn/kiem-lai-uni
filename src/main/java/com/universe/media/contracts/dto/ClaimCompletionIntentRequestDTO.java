package com.universe.media.contracts.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Request DTO for claiming completion intent on a multipart upload session.
 */
public record ClaimCompletionIntentRequestDTO(
        UUID sessionId,
        String requestingPrincipalId,
        String targetEntityType,
        UUID targetEntityId,
        String declaredContentHash,
        List<MultipartManifestPartDTO> manifest
) {
    public ClaimCompletionIntentRequestDTO {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(requestingPrincipalId, "requestingPrincipalId cannot be null.");
        Objects.requireNonNull(targetEntityType, "targetEntityType cannot be null.");
        Objects.requireNonNull(targetEntityId, "targetEntityId cannot be null.");
        Objects.requireNonNull(declaredContentHash, "declaredContentHash cannot be null.");
        Objects.requireNonNull(manifest, "manifest cannot be null.");
        manifest = List.copyOf(manifest);
    }
}
