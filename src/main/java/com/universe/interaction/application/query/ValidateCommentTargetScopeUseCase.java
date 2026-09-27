package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Application-level target-scope guard verifying that a given comment belongs to the expected target.
 *
 * <p>Protects route integrity by ensuring endpoints scoped to one target (e.g. a specific Novel chapter)
 * cannot read or mutate comments belonging to another target.
 *
 * <p>Preserves clean architecture contracts:
 * <ul>
 *   <li>Non-locking ordinary lookup via {@link CommentRepositoryPort#findById(UUID)};</li>
 *   <li>Throws {@link CommentNotFoundException} for both missing comments and target mismatches,
 *       ensuring unavailable/mismatched targets are exposed as not-found without leaking information;</li>
 *   <li>Reusable across bounded contexts (Novel, Wiki);</li>
 *   <li>Free of framework-specific persistence or web APIs.</li>
 * </ul>
 */
@Service
public class ValidateCommentTargetScopeUseCase {

    private final CommentRepositoryPort commentRepositoryPort;

    public ValidateCommentTargetScopeUseCase(CommentRepositoryPort commentRepositoryPort) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
    }

    @Transactional(readOnly = true)
    public void execute(UUID commentId, CommentTarget expectedTarget) {
        findAndValidateTarget(commentId, expectedTarget);
    }

    @Transactional(readOnly = true)
    public void executeRoot(UUID commentId, CommentTarget expectedTarget) {
        Comment comment = findAndValidateTarget(commentId, expectedTarget);
        if (!comment.isRoot()) {
            throw new CommentNotFoundException("Comment " + commentId + " is not a thread root.");
        }
    }

    private Comment findAndValidateTarget(UUID commentId, CommentTarget expectedTarget) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        if (expectedTarget == null) {
            throw new IllegalArgumentException("Expected CommentTarget cannot be null.");
        }

        Comment comment = commentRepositoryPort.findById(commentId)
                .orElseThrow(() -> new CommentNotFoundException(commentId));

        if (!comment.getTarget().equals(expectedTarget)) {
            throw new CommentNotFoundException("Comment " + commentId + " does not belong to target " + expectedTarget);
        }
        return comment;
    }
}
