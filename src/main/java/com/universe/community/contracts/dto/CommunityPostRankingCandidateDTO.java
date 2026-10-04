package com.universe.community.contracts.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight projection value representing a live Community post candidate for global feed ranking.
 */
public record CommunityPostRankingCandidateDTO(
        UUID postId,
        Instant publishedAt
) {
    public CommunityPostRankingCandidateDTO {
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Objects.requireNonNull(publishedAt, "PublishedAt timestamp cannot be null.");
    }
}
