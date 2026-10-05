package com.universe.community.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port for orchestrating deterministic cleanup of interaction data associated with a deleted Community Post.
 */
public interface CommunityPostInteractionCleanupPort {

    /**
     * Executes deterministic interaction cleanup for a deleted Community post.
     *
     * @param postId the unique identifier of the deleted community post
     * @param deletedAt timestamp of physical deletion used for evidence retention stamping
     */
    void cleanupCommunityPostInteractions(UUID postId, Instant deletedAt);
}
