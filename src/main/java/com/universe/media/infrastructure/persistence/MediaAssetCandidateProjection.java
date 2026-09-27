package com.universe.media.infrastructure.persistence;

import java.time.Instant;

/**
 * Spring Data JPA interface projection for keyset candidate discovery.
 */
public interface MediaAssetCandidateProjection {

    String getId();

    Instant getCreatedAt();
}
