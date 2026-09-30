package com.universe.community.contracts.port;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRankingCandidateDTO;

import java.time.Instant;
import java.util.Collection;
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
            Instant cursorCreatedAt,
            UUID cursorPostId,
            int limit
    );

    /**
     * Finds a keyset slice of public Community posts authored by a specific user,
     * ordered by {@code (created_at DESC, id DESC)}.
     *
     * @param authorUserId the author user UUID
     * @param cursorCreatedAt timestamp of the last seen post (null for the first page)
     * @param cursorPostId UUID of the last seen post (null for the first page)
     * @param limit maximum number of posts to fetch (must be greater than 0)
     * @return list of public post projection DTOs
     */
    List<CommunityPostPublicDTO> findAuthoredPostsKeyset(
            UUID authorUserId,
            Instant cursorCreatedAt,
            UUID cursorPostId,
            int limit
    );


    /**
     * Finds lightweight ranking candidates (postId, createdAt) for all live Community posts.
     *
     * @return list of ranking candidate DTOs
     */
    List<CommunityPostRankingCandidateDTO> findAllRankingCandidates();

    /**
     * Batch hydrates public post projections for the given post UUIDs.
     *
     * @param postIds collection of post UUIDs
     * @return list of public post projection DTOs
     */
    List<CommunityPostPublicDTO> findPublicPostsByIds(Collection<UUID> postIds);
}
