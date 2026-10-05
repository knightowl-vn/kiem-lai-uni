package com.universe.community.application.port.out;

import com.universe.community.domain.moderation.CommunityPostModerationEvent;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port for appending and retrieving moderation audit events for Community posts.
 */
public interface CommunityPostModerationEventRepositoryPort {

    /**
     * Appends an immutable moderation audit event.
     */
    CommunityPostModerationEvent save(CommunityPostModerationEvent event);

    /**
     * Retrieves the chronological moderation history for a post.
     */
    List<CommunityPostModerationEvent> findByPostIdOrderByCreatedAtAsc(UUID postId);
}
