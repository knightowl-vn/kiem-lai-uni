package com.universe.community.application.port.out;

import java.util.UUID;

/**
 * Outbound port for querying abuse report status on Community posts.
 */
public interface CommunityPostReportQueryPort {

    /**
     * Checks whether any pending abuse reports exist for the given post.
     *
     * @param postId unique post identifier
     * @return true if at least one pending report exists, false otherwise
     */
    boolean hasPendingReports(UUID postId);
}
