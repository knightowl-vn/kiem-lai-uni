package com.universe.community.contracts.port;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read projection query port for Community Posts and Revisions.
 */
public interface CommunityPostQueryPort {

    /**
     * Finds the public read projection for a Community Post by ID.
     *
     * @param postId the post UUID
     * @return Optional containing the public post DTO if found
     */
    Optional<CommunityPostPublicDTO> findPublicPostById(UUID postId);

    /**
     * Finds the public revision history for a Community Post, ordered newest-first.
     *
     * @param postId the post UUID
     * @return list of public revision DTOs
     */
    List<CommunityPostRevisionPublicDTO> findPublicRevisionHistory(UUID postId);

    /**
     * Finds a keyset slice of public Community posts ordered by {@code (created_at DESC, id DESC)}.
     *
     * @param cursorCreatedAt timestamp of the last seen post (null for the first page)
     * @param cursorPostId UUID of the last seen post (null for the first page)
     * @param limit maximum number of posts to fetch (must be greater than 0)
     * @return list of public post projection DTOs
     */
    List<CommunityPostPublicDTO> findNewestPostsKeyset(
            java.time.Instant cursorCreatedAt,
            UUID cursorPostId,
            int limit
    );
}
