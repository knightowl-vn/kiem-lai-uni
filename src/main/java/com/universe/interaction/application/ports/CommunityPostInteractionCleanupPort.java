package com.universe.interaction.application.ports;

import java.time.Instant;
import java.util.UUID;

/**
 * Port contract for deterministic cleanup of interaction data associated with a deleted Community Post.
 *
 * <p><b>Lifecycle Precondition:</b>
 * The caller (Community post deletion coordinator in MS-07B4) MUST hold the Community post mutation barrier
 * (e.g. pessimistic lock or physical post deletion boundary in Community context) prior to invoking this method
 * to ensure no concurrent root comments, post reactions, or post reports can pass eligibility and commit.
 *
 * <p><b>Cleanup Guarantees:</b>
 * <ol>
 *   <li>Discovers all comment and reply IDs associated with the target post.</li>
 *   <li>Stamps {@code targetDeletedAt} on all {@code COMMENT} reports for those comment IDs.</li>
 *   <li>Stamps {@code targetDeletedAt} on all {@code COMMUNITY_POST} reports for the post ID.</li>
 *   <li>Deletes all {@code interaction_reactions} on the discovered comment IDs.</li>
 *   <li>Deletes all {@code interaction_reactions} on the target post ID.</li>
 *   <li>Deletes all {@code interaction_comment_revisions} and {@code interaction_comments} for the post.</li>
 *   <li>Executes bounded multi-pass settlement to ensure zero orphaned in-flight entities.</li>
 * </ol>
 *
 * <p><b>Postcondition:</b>
 * Guaranteed zero post reactions, zero comments/replies, zero comment reactions, and zero comment revisions.
 * All reports targeting the post and its comments remain safely retained with {@code targetDeletedAt} populated.
 */
public interface CommunityPostInteractionCleanupPort {

    /**
     * Executes deterministic interaction cleanup for a deleted Community post.
     *
     * @param postId the unique identifier of the deleted community post
     * @param deletedAt timestamp of physical deletion used for evidence retention stamping
     */
    void cleanupCommunityPostInteractions(UUID postId, Instant deletedAt);
}
