package com.universe.screen.entry.admin.dto;

import com.universe.media.contracts.dto.MultipartManifestPartDTO;

import java.util.List;
import java.util.Objects;

/**
 * Request payload containing client-computed SHA-256 and uploaded parts manifest evidence.
 */
public record CompleteVideoUploadSessionRequest(
        String declaredContentHash,
        List<MultipartManifestPartDTO> parts
) {
    public CompleteVideoUploadSessionRequest {
        Objects.requireNonNull(declaredContentHash, "declaredContentHash cannot be null.");
        Objects.requireNonNull(parts, "parts cannot be null.");
        parts = List.copyOf(parts);
    }
}
