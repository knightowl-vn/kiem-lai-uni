package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentHasRepliesException;
import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Use case to physically hard-delete a comment by its author.
 *
 * <p>Ownership & Hard-Delete Policies:
 * <ul>
 *   <li>Normal author delete is allowed ONLY for leaf comments (comments with zero descendants);</li>
 *   <li>If the comment has one or more replies/descendants, the deletion is rejected with {@link CommentHasRepliesException};</li>
 *   <li>Reactions and revisions for the deleted leaf comment are physically purged;</li>
 *   <li>The leaf comment row is physically removed from {@code interaction_comments}.</li>
 * </ul>
 */
@Service
public class DeleteCommentUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentRevisionRepositoryPort commentRevisionRepositoryPort;
    private final ReactionRepositoryPort reactionRepositoryPort;

    public DeleteCommentUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentRevisionRepositoryPort commentRevisionRepositoryPort,
            ReactionRepositoryPort reactionRepositoryPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.commentRevisionRepositoryPort = Objects.requireNonNull(commentRevisionRepositoryPort, "CommentRevisionRepositoryPort cannot be null.");
        this.reactionRepositoryPort = Objects.requireNonNull(reactionRepositoryPort, "ReactionRepositoryPort cannot be null.");
    }

    @Transactional
    public void execute(DeleteCommentCommand command) {
        Objects.requireNonNull(command, "DeleteCommentCommand cannot be null.");

        // 1. Load current comment with row lock
        Comment comment = commentRepositoryPort.findByIdForUpdate(command.commentId())
                .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + command.commentId()));

        // 2. Author check
        if (!comment.getAuthorUserId().equals(command.actorUserId())) {
            throw new CommentMutationForbiddenException(
                    "User " + command.actorUserId() + " is not the author of comment " + command.commentId()
            );
        }

        // 3. Ownership invariant: normal author cannot delete comment if descendants exist
        if (commentRepositoryPort.hasDescendants(comment.getId())) {
            throw new CommentHasRepliesException(comment.getId());
        }

        // 4. Clean up decoupled reactions for this single leaf comment
        reactionRepositoryPort.deleteAllByTargetIds(ReactionTargetType.COMMENT, List.of(comment.getId()));

        // 5. Clean up comment revisions for this single leaf comment
        commentRevisionRepositoryPort.deleteAllByCommentIds(List.of(comment.getId()));

        // 6. Physically remove the leaf comment row
        commentRepositoryPort.deleteById(comment.getId());
    }
}
