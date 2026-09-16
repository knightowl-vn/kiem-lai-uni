package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case to orchestrate reply creation under a root or nested reply comment.
 */
@Service
public class ReplyCommentUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentTargetEligibilityPort eligibilityPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public ReplyCommentUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentTargetEligibilityPort eligibilityPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.eligibilityPort = Objects.requireNonNull(eligibilityPort, "CommentTargetEligibilityPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public Comment execute(ReplyCommentCommand command) {
        Objects.requireNonNull(command, "ReplyCommentCommand cannot be null.");

        // 1. Load immediate parent with row lock
        Comment parent = commentRepositoryPort.findByIdForUpdate(command.parentCommentId())
                .orElseThrow(() -> new CommentNotFoundException("Parent comment not found: " + command.parentCommentId()));

        // 2. Parent must currently be ACTIVE
        if (parent.isDeleted()) {
            throw new CommentMutationForbiddenException("Cannot reply to a deleted comment: " + command.parentCommentId());
        }

        // 3. Resolve and validate thread root
        Comment threadRoot;
        if (parent.isRoot()) {
            threadRoot = parent;
        } else {
            UUID threadRootCommentId = parent.getThreadRootCommentId();
            if (threadRootCommentId == null) {
                throw new CommentThreadIntegrityException("Parent reply is missing threadRootCommentId: " + parent.getId());
            }

            // Lock thread root with row lock
            threadRoot = commentRepositoryPort.findByIdForUpdate(threadRootCommentId)
                    .orElseThrow(() -> new CommentThreadIntegrityException("Thread root comment not found for reply: " + threadRootCommentId));

            if (!threadRoot.isRoot()) {
                throw new CommentThreadIntegrityException("Resolved thread root is not a root comment: " + threadRootCommentId);
            }

            if (!Objects.equals(threadRoot.getTarget(), parent.getTarget())) {
                throw new CommentThreadIntegrityException("Parent target does not match thread root target.");
            }

            if (threadRoot.isDeleted()) {
                throw new CommentMutationForbiddenException("Cannot reply in a deleted discussion thread: " + threadRootCommentId);
            }
        }

        // 4. Verify target remains eligible for comments
        if (!eligibilityPort.isEligible(parent.getTarget())) {
            throw new CommentTargetNotEligibleException(parent.getTarget());
        }

        // 5. Capture creation time once and generate reply ID
        Instant createdAt = clockPort.now();
        UUID replyId = idGeneratorPort.generate();

        // 6. Construct reply aggregate
        Comment reply = Comment.createReply(
                replyId,
                parent,
                command.actorUserId(),
                command.body(),
                createdAt
        );

        // 7. Save and return
        return commentRepositoryPort.save(reply);
    }
}
