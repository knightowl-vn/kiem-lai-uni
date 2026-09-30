package com.universe.interaction.application.ports;

import com.universe.interaction.domain.CommentRevision;

import java.util.UUID;

/**
 * Framework-free repository port defining persistence operations on {@link CommentRevision}.
 *
 * <p>Preserves clean architecture boundaries and transactional invariants:
 * <ul>
 *   <li>{@link #save(CommentRevision)} is intended for the outer {@code EditCommentUseCase} transaction in G5C;</li>
 *   <li>{@link #getNextRevisionNumber(UUID)} is correct only while the parent {@code Comment} row is already locked by the caller;</li>
 *   <li>{@link #deleteAllByCommentId(UUID)} is intended for the outer {@code DeleteCommentUseCase} transaction in G5C;</li>
 *   <li>{@link #findSliceByCommentId(UUID, int, int)} provides zero-based slice lookup ordered newest-first.</li>
 * </ul>
 */
public interface CommentRevisionRepositoryPort {

    /**
     * Persists a newly created comment revision.
     *
     * @param revision the revision to save
     * @return the persisted revision
     */
    CommentRevision save(CommentRevision revision);

    /**
     * Computes the next monotonic revision number for a comment.
     *
     * <p>Precondition: The caller must hold an exclusive pessimistic row lock on the parent
     * {@code Comment} row to guarantee race safety.
     *
     * @param commentId the ID of the parent comment
     * @return the next revision number (1 if no prior revisions exist)
     */
    int getNextRevisionNumber(UUID commentId);

    /**
     * Retrieves a zero-based slice of comment revisions ordered newest-first ({@code revisionNumber DESC, id DESC}).
     *
     * @param commentId the ID of the parent comment
     * @param page zero-based page index
     * @param size page size (must be &gt; 0)
     * @return immutable slice of revisions
     */
    CommentRevisionSlice findSliceByCommentId(UUID commentId, int page, int size);

    /**
     * Deletes all revisions associated with the specified comment.
     *
     * <p>Intended for invocation within the comment deletion transaction.
     *
     * @param commentId the ID of the comment whose revisions should be purged
     */
    void deleteAllByCommentId(UUID commentId);

    /**
     * Deletes all revisions associated with a collection of comments.
     *
     * @param commentIds collection of comment IDs whose revisions should be purged
     */
    void deleteAllByCommentIds(java.util.Collection<UUID> commentIds);

    /**
     * Counts the total number of revisions associated with a collection of comment IDs.
     *
     * @param commentIds collection of comment IDs
     * @return count of revisions
     */
    long countByCommentIds(java.util.Collection<UUID> commentIds);
}
