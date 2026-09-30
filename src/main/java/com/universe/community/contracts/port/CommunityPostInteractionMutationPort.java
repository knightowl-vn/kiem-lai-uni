package com.universe.community.contracts.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Public contract port exposed by the Community bounded context to coordinate
 * mutation concurrency and deletion barriers for Interaction operations on Community posts.
 *
 * <p>Preserves bounded context boundaries:
 * <ul>
 *   <li>Allows Interaction mutation transactions to acquire an exclusive {@code PESSIMISTIC_WRITE}
 *       row lock on the target {@code CommunityPost} entity;</li>
 *   <li>The lock joins the caller's existing Spring transaction via {@code Propagation.MANDATORY};</li>
 *   <li>Returns an immutable projection {@link CommunityPostLockedView} containing author and caption;</li>
 *   <li>Fails closed (returns empty) if the post has been physically deleted or does not exist.</li>
 * </ul>
 */
public interface CommunityPostInteractionMutationPort {

    /**
     * Acquires an exclusive {@code PESSIMISTIC_WRITE} row lock on the specified CommunityPost
     * within the caller's active transaction, returning an immutable view of the locked post.
     *
     * @param postId the unique post UUID
     * @return an Optional containing the locked post view, or empty if the post does not exist
     */
    Optional<CommunityPostLockedView> lockExistingPostForInteraction(UUID postId);

    /**
     * Immutable projection of the locked Community post's scalar fields required for interaction validation.
     */
    record CommunityPostLockedView(
            UUID postId,
            UUID authorUserId,
            String caption
    ) {}
}
