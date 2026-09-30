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
     * Batch retrieves reaction count, comment count, and engagement score for the given post IDs.
     *
     * @param postIds collection of post UUIDs
     * @return map of post UUID to engagement counts DTO
     */
    Map<UUID, CommunityPostEngagementCountsDTO> getEngagementCountsForPosts(Collection<UUID> postIds);
}
