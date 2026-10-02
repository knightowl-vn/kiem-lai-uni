package com.universe.community.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound port for retrieving raw interaction engagement counts for Community Posts.
 */
public interface CommunityPostEngagementMetricsPort {

    /**
     * Retrieves raw reaction, active comment counts, and viewer reaction for the given post IDs.
     * This is the authoritative abstract method of this port.
     *
     * @param postIds collection of post UUIDs
     * @param viewerUserId optional viewer user ID (null for guests)
     * @return map of post UUID to raw engagement metrics
     */
    Map<UUID, PostEngagementMetrics> getEngagementMetricsForPosts(Collection<UUID> postIds, UUID viewerUserId);

    /**
     * Convenience method for viewer-less calls. Delegates to {@link #getEngagementMetricsForPosts(Collection, UUID)}
     * with a null viewerUserId.
     *
     * @param postIds collection of post UUIDs
     * @return map of post UUID to raw engagement metrics
     */
    default Map<UUID, PostEngagementMetrics> getEngagementMetricsForPosts(Collection<UUID> postIds) {
        return getEngagementMetricsForPosts(postIds, null);
    }

    /**
     * Raw engagement counts supplied by Interaction context.
     */
    record PostEngagementMetrics(long reactionCount, long commentCount, String currentUserReaction) {
        public PostEngagementMetrics {
            if (reactionCount < 0) {
                throw new IllegalArgumentException("Reaction count cannot be negative: " + reactionCount);
            }
            if (commentCount < 0) {
                throw new IllegalArgumentException("Comment count cannot be negative: " + commentCount);
            }
        }

        public PostEngagementMetrics(long reactionCount, long commentCount) {
            this(reactionCount, commentCount, null);
        }
    }
}
