package com.universe.media.contracts.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Request DTO for completing a multipart upload on storage provider and registering the Media asset.
 */
public record CompleteMultipartUploadRequestDTO(
        UUID sessionId,
        String requestingPrincipalId,
        List<MultipartManifestPartDTO> manifest
) {
    public CompleteMultipartUploadRequestDTO {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(requestingPrincipalId, "requestingPrincipalId cannot be null.");
        manifest = (manifest != null) ? List.copyOf(manifest) : null;
    }
}
