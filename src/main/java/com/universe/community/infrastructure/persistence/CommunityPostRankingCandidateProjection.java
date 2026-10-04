package com.universe.community.infrastructure.persistence;

import java.time.Instant;

/**
 * Spring Data JPA projection interface for retrieving lightweight ranking candidate fields.
 */
public interface CommunityPostRankingCandidateProjection {
    String getId();
    Instant getPublishedAt();
}
