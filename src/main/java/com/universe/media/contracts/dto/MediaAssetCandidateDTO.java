package com.universe.media.contracts.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight public candidate DTO representing an active media asset matching a client tag query.
 */
public record MediaAssetCandidateDTO(
        UUID assetId,
        Instant createdAt
) {
    public MediaAssetCandidateDTO {
        Objects.requireNonNull(
                assetId,
                "Asset ID cannot be null."
        );
        Objects.requireNonNull(
                createdAt,
                "Created at timestamp cannot be null."
        );
    }
}
