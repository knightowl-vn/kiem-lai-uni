package com.universe.interaction.application.ports;

import com.universe.interaction.application.query.CommunityPostEngagementCountsDTO;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Output port for querying aggregated interaction engagement metrics (reactions, comments, total engagement) for Community Posts.
 */
public interface CommunityPostEngagementQueryPort {

    /**
     * Batch retrieves reaction count, comment count, and viewer reaction for the given post IDs.
     * This is the authoritative abstract method of this port.
     *
     * @param postIds collection of post UUIDs
     * @param viewerUserId optional viewer user ID (null for guests)
     * @return map of post UUID to engagement counts DTO
     */
    Map<UUID, CommunityPostEngagementCountsDTO> getEngagementCountsForPosts(Collection<UUID> postIds, UUID viewerUserId);

    /**
     * Batch retrieves reaction count, comment count, and engagement score for the given post IDs (viewer-less convenience).
     * Delegates to {@link #getEngagementCountsForPosts(Collection, UUID)} with a null viewerUserId.
     *
     * @param postIds collection of post UUIDs
     * @return map of post UUID to engagement counts DTO
     */
    default Map<UUID, CommunityPostEngagementCountsDTO> getEngagementCountsForPosts(Collection<UUID> postIds) {
        return getEngagementCountsForPosts(postIds, null);
    }
}
