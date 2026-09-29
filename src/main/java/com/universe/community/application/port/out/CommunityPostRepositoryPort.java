package com.universe.community.application.port.out;

import com.universe.community.domain.CommunityPost;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for managing {@link CommunityPost} aggregate root persistence.
 */
public interface CommunityPostRepositoryPort {

    /**
     * Saves a CommunityPost aggregate (insert or update).
     *
     * @param post the domain aggregate to save
     * @return the saved domain aggregate
     */
    CommunityPost save(CommunityPost post);

    /**
     * Finds a CommunityPost by its ID.
     *
     * @param postId the post UUID
     * @return an Optional containing the domain aggregate if found
     */
    Optional<CommunityPost> findById(UUID postId);

    /**
     * Finds a CommunityPost by its ID with a pessimistic write lock for concurrent mutation.
     *
     * @param postId the post UUID
     * @return an Optional containing the domain aggregate if found
     */
    Optional<CommunityPost> findByIdForUpdate(UUID postId);

    /**
     * Checks if a post exists by its ID.
     *
     * @param postId the post UUID
     * @return true if the post exists
     */
    boolean existsById(UUID postId);

    /**
     * Deletes a post by its ID.
     *
     * @param postId the post UUID
     */
    void deleteById(UUID postId);
}
