package com.universe.interaction.application.ports;

import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for Comment aggregate persistence and retrieval.
 *
 * <p>Preserves clean architecture principles:
 * <ul>
 *   <li>Exposes only pure domain models and framework-free slice boundary;</li>
 *   <li>Keeps Spring Data / JPA pagination constructs inside infrastructure;</li>
 *   <li>Avoids recursive tree hydration in favor of flat thread queries and deterministic root slice pagination.</li>
 * </ul>
 */
public interface CommentRepositoryPort {

    /**
     * Persists a comment aggregate.
     *
     * @param comment domain comment to persist (cannot be null)
     * @return persisted domain comment
     */
    Comment save(Comment comment);

    /**
     * Finds a comment by its ID, supporting both ACTIVE comments and DELETED tombstones.
     *
     * @param commentId unique identifier of the comment
     * @return optional containing the domain comment if found, empty otherwise
     */
    Optional<Comment> findById(UUID commentId);

    /**
     * Finds a comment by its ID with an exclusive row lock for update,
     * supporting both ACTIVE comments and DELETED tombstones.
     *
     * <p>Callers must invoke this method inside an active mutation transaction
     * so that the exclusive lock remains held through validation, mutation, and save.
     *
     * @param commentId unique identifier of the comment (cannot be null)
     * @return optional containing the domain comment if found, empty otherwise
     */
    Optional<Comment> findByIdForUpdate(UUID commentId);

    /**
     * Finds active root comments for the given target using zero-based slice pagination.
     *
     * <p>Roots have {@code parent_comment_id IS NULL} and {@code status = 'ACTIVE'}.
     * Ordered deterministically by {@code createdAt DESC, id DESC}.
     *
     * @param target target entity (cannot be null)
     * @param page zero-based page index (>= 0)
     * @param size page size (> 0)
     * @return immutable slice of root comments
     */
    CommentSlice findActiveRoots(CommentTarget target, int page, int size);

    /**
     * Finds all replies for a thread root comment, ordered chronologically.
     *
     * <p>Includes both ACTIVE and DELETED replies.
     * Ordered deterministically by {@code createdAt ASC, id ASC}.
     *
     * @param threadRootCommentId unique identifier of the thread root comment
     * @return list of reply domain comments
     */
    List<Comment> findThreadReplies(UUID threadRootCommentId);

    /**
     * Finds the IDs of all active root comments for the given target.
     *
     * <p>Roots have {@code parent_comment_id IS NULL} and {@code status = 'ACTIVE'}.
     *
     * @param target target entity (cannot be null)
     * @return list of active root comment IDs
     */
    List<UUID> findActiveRootCommentIds(CommentTarget target);
}
