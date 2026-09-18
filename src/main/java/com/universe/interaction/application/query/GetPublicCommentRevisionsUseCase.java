package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Application query use case to retrieve the public revision history for an active comment.
 *
 * <p>Preserves consumer-neutral Interaction architecture and strict privacy invariants:
 * <ul>
 *   <li>Verifies that the target comment exists and belongs to the expected target;</li>
 *   <li>Enforces that target root comments must be {@code ACTIVE};</li>
 *   <li>Enforces that target reply comments must be {@code ACTIVE}, their thread root must exist,
 *       belong to the same target, and be {@code ACTIVE};</li>
 *   <li>Intermediate ancestor tombstones do not hide an otherwise active descendant under an active root;</li>
 *   <li>Converges all missing, deleted, mismatched-target, or hidden states to {@link CommentNotFoundException}
 *       to prevent enumeration and timing leakage;</li>
 *   <li>Returns read-only slice-paginated {@link CommentRevisionSlice} without issuing count queries.</li>
 * </ul>
 */
@Service
public class GetPublicCommentRevisionsUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentRevisionRepositoryPort commentRevisionRepositoryPort;

    public GetPublicCommentRevisionsUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentRevisionRepositoryPort commentRevisionRepositoryPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.commentRevisionRepositoryPort = Objects.requireNonNull(commentRevisionRepositoryPort, "CommentRevisionRepositoryPort cannot be null.");
    }

    @Transactional(readOnly = true)
    public CommentRevisionSlice execute(
            UUID commentId,
            CommentTarget expectedTarget,
            int page,
            int size
    ) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        if (expectedTarget == null) {
            throw new IllegalArgumentException("Expected CommentTarget cannot be null.");
        }
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }

        // 1. Find target comment
        Comment comment = commentRepositoryPort.findById(commentId)
                .orElseThrow(() -> new CommentNotFoundException(commentId));

        // 2. Validate target ownership
        if (!comment.getTarget().equals(expectedTarget)) {
            throw new CommentNotFoundException(commentId);
        }

        // 3. Target comment itself must be ACTIVE
        if (comment.isDeleted()) {
            throw new CommentNotFoundException(commentId);
        }

        // 4. If comment is a reply, validate thread root hierarchy and status
        if (!comment.isRoot()) {
            UUID rootId = comment.getThreadRootCommentId();
            if (rootId == null) {
                throw new CommentNotFoundException(commentId);
            }
            Comment rootComment = commentRepositoryPort.findById(rootId)
                    .orElseThrow(() -> new CommentNotFoundException(commentId));

            if (!rootComment.isRoot() || !rootComment.getTarget().equals(expectedTarget) || rootComment.isDeleted()) {
                throw new CommentNotFoundException(commentId);
            }
        }

        // 5. Retrieve revisions slice (ordered newest-first)
        return commentRevisionRepositoryPort.findSliceByCommentId(commentId, page, size);
    }
}
