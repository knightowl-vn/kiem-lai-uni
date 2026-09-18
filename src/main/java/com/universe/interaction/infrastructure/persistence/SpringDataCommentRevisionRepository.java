package com.universe.interaction.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link CommentRevisionJpaEntity}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Querying current maximum revision number for a comment;</li>
 *   <li>Zero-based slice pagination ordered newest-first without COUNT(*) queries;</li>
 *   <li>Bulk deletion of all revisions belonging to a logically deleted comment.</li>
 * </ul>
 */
@Repository
public interface SpringDataCommentRevisionRepository extends JpaRepository<CommentRevisionJpaEntity, String> {

    /**
     * Computes the maximum revision number for a given comment, or 0 if no revisions exist.
     */
    @Query("SELECT COALESCE(MAX(r.revisionNumber), 0) FROM CommentRevisionJpaEntity r WHERE r.commentId = :commentId")
    int findMaxRevisionNumberByCommentId(@Param("commentId") String commentId);

    /**
     * Retrieves a pageable slice of revisions for a given comment, ordered newest-first.
     *
     * <p>Deterministic ordering by {@code revision_number DESC, id DESC} uses the composite index
     * {@code idx_interaction_comment_revisions_comment_number}.
     */
    @Query("""
            SELECT r FROM CommentRevisionJpaEntity r
            WHERE r.commentId = :commentId
            ORDER BY r.revisionNumber DESC, r.id DESC
            """)
    Slice<CommentRevisionJpaEntity> findSliceByCommentId(
            @Param("commentId") String commentId,
            Pageable pageable
    );

    /**
     * Deletes all revisions associated with a given comment.
     */
    @Modifying
    @Query("DELETE FROM CommentRevisionJpaEntity r WHERE r.commentId = :commentId")
    void deleteAllByCommentId(@Param("commentId") String commentId);
}
