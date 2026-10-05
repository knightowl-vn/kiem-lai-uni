package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import com.universe.notification.domain.NotificationType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    private final NotificationDispatchPort notificationDispatchPort;

    public ReplyCommentUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentTargetEligibilityPort eligibilityPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort,
            NotificationDispatchPort notificationDispatchPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.eligibilityPort = Objects.requireNonNull(eligibilityPort, "CommentTargetEligibilityPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.notificationDispatchPort = Objects.requireNonNull(notificationDispatchPort, "NotificationDispatchPort cannot be null.");
    }

    @Transactional
    public Comment execute(ReplyCommentCommand command) {
        Objects.requireNonNull(command, "ReplyCommentCommand cannot be null.");

        // 1. Resolve parent without lock to determine tree hierarchy
        Comment initialParent = commentRepositoryPort.findById(command.parentCommentId())
                .orElseThrow(() -> new CommentNotFoundException("Parent comment not found: " + command.parentCommentId()));

        if (initialParent.isDeleted()) {
            throw new CommentMutationForbiddenException("Cannot reply to a deleted comment: " + command.parentCommentId());
        }

        Comment parent;
        Comment threadRoot;

        // 2. Acquire locks in canonical total ID ASC order (matching database lock ordering)
        if (initialParent.isRoot()) {
            parent = commentRepositoryPort.findByIdForUpdate(initialParent.getId())
                    .orElseThrow(() -> new CommentNotFoundException("Parent comment not found: " + initialParent.getId()));
            if (parent.isDeleted()) {
                throw new CommentMutationForbiddenException("Cannot reply to a deleted comment: " + initialParent.getId());
            }
            threadRoot = parent;
        } else {
            UUID threadRootCommentId = initialParent.getThreadRootCommentId();
            if (threadRootCommentId == null) {
                throw new CommentThreadIntegrityException("Parent reply is missing threadRootCommentId: " + initialParent.getId());
            }

            // Step 2a: Sort the two IDs into canonical total order (ID ASC)
            List<UUID> orderedIds = CommentLockOrder.inLockOrder(threadRootCommentId, initialParent.getId());

            // Step 2b: Acquire pessimistic write locks in canonical total order
            Comment first = commentRepositoryPort.findByIdForUpdate(orderedIds.get(0))
                    .orElseThrow(() -> orderedIds.get(0).equals(threadRootCommentId)
                            ? new CommentThreadIntegrityException("Thread root comment not found for reply: " + threadRootCommentId)
                            : new CommentNotFoundException("Parent comment not found: " + initialParent.getId()));

            Comment second = commentRepositoryPort.findByIdForUpdate(orderedIds.get(1))
                    .orElseThrow(() -> orderedIds.get(1).equals(threadRootCommentId)
                            ? new CommentThreadIntegrityException("Thread root comment not found for reply: " + threadRootCommentId)
                            : new CommentNotFoundException("Parent comment not found: " + initialParent.getId()));

            // Step 2c: Map locked entities back to semantic roles
            if (first.getId().equals(threadRootCommentId)) {
                threadRoot = first;
                parent = second;
            } else {
                parent = first;
                threadRoot = second;
            }

            // Step 2d: Revalidate semantic invariants under authoritative row locks
            if (threadRoot.isDeleted()) {
                throw new CommentMutationForbiddenException("Cannot reply in a deleted discussion thread: " + threadRootCommentId);
            }
            if (!threadRoot.isRoot()) {
                throw new CommentThreadIntegrityException("Resolved thread root is not a root comment: " + threadRootCommentId);
            }
            if (parent.isDeleted()) {
                throw new CommentMutationForbiddenException("Cannot reply to a deleted comment: " + command.parentCommentId());
            }
            if (!Objects.equals(parent.getThreadRootCommentId(), threadRoot.getId())) {
                throw new CommentThreadIntegrityException("Parent thread root does not match locked thread root.");
            }
            if (!Objects.equals(threadRoot.getTarget(), parent.getTarget())) {
                throw new CommentThreadIntegrityException("Parent target does not match thread root target.");
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

        // 7. Save reply
        Comment savedReply = commentRepositoryPort.save(reply);

        // 8. MS-05K3: Dispatch direct COMMENT_REPLY notification
        // Self-reply suppression: User replying to their own comment does NOT notify themselves
        if (!Objects.equals(command.actorUserId(), parent.getAuthorUserId())) {
            UUID threadRootId = savedReply.getThreadRootCommentId();
            NotificationDispatchCommand notificationCommand = new NotificationDispatchCommand(
                    parent.getAuthorUserId(),
                    NotificationType.COMMENT_REPLY,
                    command.actorUserId(),
                    null,
                    parent.getTargetType().name(),
                    parent.getTargetId(),
                    null,
                    savedReply.getId(),
                    threadRootId,
                    null,
                    "COMMENT_REPLY:" + savedReply.getId()
            );
            notificationDispatchPort.dispatch(notificationCommand);
        }

        return savedReply;
    }
}
