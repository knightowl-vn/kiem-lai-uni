package com.universe.interaction.application.mutation;

import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentNotReportableException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case orchestrating submission of a user report against a generalized interaction target
 * ({@link ReportTargetType#COMMENT} or {@link ReportTargetType#COMMUNITY_POST}).
 */
@Service
@Transactional
public class SubmitInteractionReportUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommunityPostInteractionMutationPort communityPostMutationPort;
    private final InteractionReportRepositoryPort reportRepositoryPort;
    private final CommentTargetEligibilityPort eligibilityPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public SubmitInteractionReportUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommunityPostInteractionMutationPort communityPostMutationPort,
            InteractionReportRepositoryPort reportRepositoryPort,
            CommentTargetEligibilityPort eligibilityPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.communityPostMutationPort = Objects.requireNonNull(communityPostMutationPort, "CommunityPostInteractionMutationPort cannot be null.");
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "InteractionReportRepositoryPort cannot be null.");
        this.eligibilityPort = Objects.requireNonNull(eligibilityPort, "CommentTargetEligibilityPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public InteractionReport execute(SubmitInteractionReportCommand command) {
        Objects.requireNonNull(command, "SubmitInteractionReportCommand cannot be null.");

        // 1. Application pre-check for existing pending report by this reporter on this target
        if (reportRepositoryPort.existsPendingByTargetAndReporter(
                command.targetType(),
                command.targetId(),
                command.reporterUserId()
        )) {
            throw new DuplicatePendingReportException(
                    command.targetType(),
                    command.targetId(),
                    command.reporterUserId()
            );
        }

        // 2. Validate target and capture snapshot based on target type
        String contentSnapshot = switch (command.targetType()) {
            case COMMENT -> validateAndSnapshotComment(command.targetId(), command.reporterUserId());
            case COMMUNITY_POST -> validateAndSnapshotCommunityPost(command.targetId(), command.reporterUserId());
        };

        // 3. Generate ID, capture snapshot and timestamp
        UUID reportId = idGeneratorPort.generate();
        Instant createdAt = clockPort.now();

        InteractionReport report = InteractionReport.createPending(
                reportId,
                command.targetType(),
                command.targetId(),
                command.reporterUserId(),
                command.reason(),
                command.description(),
                contentSnapshot,
                createdAt
        );

        return reportRepositoryPort.save(report);
    }

    private String validateAndSnapshotComment(UUID commentId, UUID reporterUserId) {
        // Step A: Initial non-locking discovery
        Comment initialComment = commentRepositoryPort.findById(commentId)
                .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + commentId));

        if (initialComment.isDeleted()) {
            throw new CommentNotReportableException(commentId, "Comment is deleted.");
        }

        Comment reportedComment;
        Comment threadRoot = null;

        if (initialComment.isRoot()) {
            // Step B: Root comment report - acquire single row lock
            reportedComment = commentRepositoryPort.findByIdForUpdate(commentId)
                    .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + commentId));

            if (reportedComment.isDeleted()) {
                throw new CommentNotReportableException(reportedComment.getId(), "Comment is deleted.");
            }
            if (!reportedComment.isRoot()) {
                throw new CommentNotReportableException(reportedComment.getId(), "Comment hierarchy changed.");
            }
        } else {
            // Step C: Reply report - acquire both rows strictly in canonical ID ASC order
            UUID threadRootCommentId = initialComment.getThreadRootCommentId();
            if (threadRootCommentId == null) {
                throw new CommentNotReportableException(initialComment.getId(), "Reply is missing thread root reference.");
            }

            java.util.List<UUID> orderedIds = CommentLockOrder.inLockOrder(threadRootCommentId, initialComment.getId());

            Comment first = commentRepositoryPort.findByIdForUpdate(orderedIds.get(0))
                    .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + orderedIds.get(0)));

            Comment second = commentRepositoryPort.findByIdForUpdate(orderedIds.get(1))
                    .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + orderedIds.get(1)));

            // Step D: Map locked rows back to semantic roles
            if (first.getId().equals(commentId)) {
                reportedComment = first;
                threadRoot = second;
            } else {
                reportedComment = second;
                threadRoot = first;
            }

            // Step E: Revalidate invariants under authoritative row locks
            if (reportedComment.isDeleted()) {
                throw new CommentNotReportableException(reportedComment.getId(), "Comment is deleted.");
            }
            if (!reportedComment.isReply()) {
                throw new CommentNotReportableException(reportedComment.getId(), "Reported comment is not a reply.");
            }
            if (!Objects.equals(reportedComment.getThreadRootCommentId(), threadRoot.getId())) {
                throw new CommentNotReportableException(reportedComment.getId(), "Reply thread root reference mismatch.");
            }
            if (threadRoot.isDeleted()) {
                throw new CommentNotReportableException(reportedComment.getId(), "Discussion thread is deleted.");
            }
            if (!threadRoot.isRoot()) {
                throw new CommentNotReportableException(reportedComment.getId(), "Resolved thread root is not a root comment.");
            }
            if (!Objects.equals(reportedComment.getTarget(), threadRoot.getTarget())) {
                throw new CommentNotReportableException(reportedComment.getId(), "Reply target does not match thread root target.");
            }
        }

        // Self-report check
        if (reporterUserId.equals(reportedComment.getAuthorUserId())) {
            throw new SelfReportNotAllowedException(ReportTargetType.COMMENT, reportedComment.getId(), reporterUserId);
        }

        // Target publication / eligibility check
        if (!eligibilityPort.isEligible(reportedComment.getTarget())) {
            throw new CommentTargetNotEligibleException(reportedComment.getTarget());
        }

        return reportedComment.getBody();
    }

    private String validateAndSnapshotCommunityPost(UUID postId, UUID reporterUserId) {
        CommunityPostInteractionMutationPort.CommunityPostLockedView post = communityPostMutationPort
                .lockExistingPostForInteraction(postId)
                .orElseThrow(() -> new CommentTargetNotEligibleException(CommentTarget.communityPost(postId)));

        // Self-report check using locked author
        if (reporterUserId.equals(post.authorUserId())) {
            throw new SelfReportNotAllowedException(ReportTargetType.COMMUNITY_POST, postId, reporterUserId);
        }

        // Content snapshot using locked caption
        return post.caption();
    }
}
