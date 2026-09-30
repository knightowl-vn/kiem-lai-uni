package com.universe.community.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound port for retrieving raw interaction engagement counts for Community Posts.
 */
public interface CommunityPostEngagementMetricsPort {

    /**
     * Retrieves raw reaction and active comment counts for the given post IDs.
     *
     * @param postIds collection of post UUIDs
     * @return map of post UUID to raw engagement metrics
     */
    Map<UUID, PostEngagementMetrics> getEngagementMetricsForPosts(Collection<UUID> postIds);

    /**
     * Raw engagement counts supplied by Interaction context.
     */
    record PostEngagementMetrics(long reactionCount, long commentCount) {
        public PostEngagementMetrics {
            if (reactionCount < 0) {
                throw new IllegalArgumentException("Reaction count cannot be negative: " + reactionCount);
            }
            if (commentCount < 0) {
                throw new IllegalArgumentException("Comment count cannot be negative: " + commentCount);
            }
        }
    }
}
