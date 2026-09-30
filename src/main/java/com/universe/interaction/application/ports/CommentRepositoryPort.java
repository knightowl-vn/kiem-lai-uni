package com.universe.interaction.application.ports;

import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * Finds active root comments matching the specified IDs for the given target.
     *
     * <p>Roots have {@code parent_comment_id IS NULL} and {@code status = 'ACTIVE'}.
     * Ordered deterministically by {@code createdAt DESC, id DESC}.
     *
     * @param target target entity (cannot be null)
     * @param rootCommentIds collection of root comment IDs to find
     * @return list of active root domain comments
     */
    List<Comment> findActiveRootsByIds(CommentTarget target, Collection<UUID> rootCommentIds);

    /**
     * Finds all replies for a collection of thread root comments, ordered chronologically.
     *
     * <p>Includes both ACTIVE and DELETED replies.
     * Ordered deterministically by {@code createdAt ASC, id ASC}.
     *
     * @param threadRootCommentIds collection of thread root comment IDs
     * @return list of reply domain comments
     */
    List<Comment> findThreadRepliesByRootIds(Collection<UUID> threadRootCommentIds);

    /**
     * Counts visible active replies grouped by thread root ID.
     *
     * <p>Excludes tombstones and deleted replies.
     *
     * @param threadRootCommentIds collection of thread root comment IDs
     * @return map of root comment UUID to active reply count
     */
    Map<UUID, Long> countActiveRepliesByThreadRootIds(Collection<UUID> threadRootCommentIds);

    /**
     * Retrieves aggregated discussion metrics for the given target directly from persistence.
     *
     * <p>Calculates threadCount (active roots) and commentCount (active roots + active replies under active roots)
     * in constant query count without loading comment IDs or entities into memory.
     *
     * @param target target entity (cannot be null)
     * @return immutable metrics for the target
     */
    CommentTargetMetrics getMetricsForTarget(CommentTarget target);

    /**
     * Checks whether the specified comment has any direct or indirect descendants (replies).
     *
     * @param commentId unique identifier of the comment (cannot be null)
     * @return true if one or more descendant replies exist, false otherwise
     */
    boolean hasDescendants(UUID commentId);

    /**
     * Physically deletes a single comment row by its unique ID.
     *
     * @param commentId unique identifier of the comment to delete (cannot be null)
     */
    void deleteById(UUID commentId);

    /**
     * Physically deletes multiple comment rows by their unique IDs.
     *
     * @param commentIds collection of comment IDs to delete
     */
    void deleteAllByIds(Collection<UUID> commentIds);

    /**
     * Retrieves ALL comment IDs for a given target regardless of status or hierarchy.
     *
     * @param targetType the target type
     * @param targetId the target ID
     * @return list of comment UUIDs
     */
    List<UUID> findAllCommentIdsByTarget(com.universe.interaction.domain.CommentTargetType targetType, UUID targetId);

    /**
     * Counts active comments and replies grouped by targetId for multiple targets.
     *
     * @param targetType the target type
     * @param targetIds collection of target IDs
     * @return map of target ID to active comment count
     */
    Map<UUID, Long> countActiveCommentsByTargetIds(
            com.universe.interaction.domain.CommentTargetType targetType,
            Collection<UUID> targetIds
    );

    /**
     * Finds and locks all comments for a target with an exclusive pessimistic write lock,
     * ordered deterministically by createdAt ASC, id ASC.
     *
     * <p>Precondition: Must be invoked within an active mutation transaction.
     *
     * @param targetType the target type
     * @param targetId the target ID
     * @return list of locked domain comments
     */
    List<Comment> lockAllCommentsByTarget(
            com.universe.interaction.domain.CommentTargetType targetType,
            UUID targetId
    );
}
