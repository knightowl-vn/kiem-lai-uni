package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.CommentNotReportableException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case orchestrating submission of a user report against an interaction comment.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Application-level duplicate pending report pre-check;</li>
 *   <li>Authoritative target comment retrieval with pessimistic lock;</li>
 *   <li>Self-report prevention (reporters cannot report their own comments);</li>
 *   <li>Public reportability (deleted comments or replies under deleted/missing thread roots cannot be reported);</li>
 *   <li>Target publication and eligibility verification;</li>
 *   <li>Authoritative current comment body snapshot capture without trusting client input;</li>
 *   <li>Decoupled ID and timestamp generation via ports.</li>
 * </ul>
 */
@Service
public class SubmitCommentReportUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final InteractionReportRepositoryPort reportRepositoryPort;
    private final CommentTargetEligibilityPort eligibilityPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public SubmitCommentReportUseCase(
            CommentRepositoryPort commentRepositoryPort,
            InteractionReportRepositoryPort reportRepositoryPort,
            CommentTargetEligibilityPort eligibilityPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "InteractionReportRepositoryPort cannot be null.");
        this.eligibilityPort = Objects.requireNonNull(eligibilityPort, "CommentTargetEligibilityPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public InteractionReport execute(SubmitCommentReportCommand command) {
        Objects.requireNonNull(command, "SubmitCommentReportCommand cannot be null.");

        // 1. Application pre-check for existing pending report by this reporter on this comment
        if (reportRepositoryPort.existsPendingByCommentIdAndReporterUserId(command.commentId(), command.reporterUserId())) {
            throw new DuplicatePendingReportException(command.commentId(), command.reporterUserId());
        }

        // 2. Load authoritative comment with pessimistic write lock
        Comment comment = commentRepositoryPort.findByIdForUpdate(command.commentId())
                .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + command.commentId()));

        // 3. Self-report check
        if (command.reporterUserId().equals(comment.getAuthorUserId())) {
            throw new SelfReportNotAllowedException(comment.getId(), command.reporterUserId());
        }

        // 4. Reportability / visibility check
        if (comment.isDeleted()) {
            throw new CommentNotReportableException(comment.getId(), "Comment is deleted.");
        }

        if (comment.isReply()) {
            UUID threadRootCommentId = comment.getThreadRootCommentId();
            if (threadRootCommentId == null) {
                throw new CommentNotReportableException(comment.getId(), "Reply is missing thread root reference.");
            }
            Comment threadRoot = commentRepositoryPort.findByIdForUpdate(threadRootCommentId)
                    .orElseThrow(() -> new CommentNotReportableException(comment.getId(), "Thread root comment not found: " + threadRootCommentId));
            if (threadRoot.isDeleted()) {
                throw new CommentNotReportableException(comment.getId(), "Discussion thread is deleted.");
            }
        }

        // 5. Target publication / eligibility check
        if (!eligibilityPort.isEligible(comment.getTarget())) {
            throw new CommentTargetNotEligibleException(comment.getTarget());
        }

        // 6. Generate ID, capture current authoritative body snapshot and timestamp
        UUID reportId = idGeneratorPort.generate();
        Instant createdAt = clockPort.now();

        InteractionReport report = InteractionReport.createPending(
                reportId,
                comment.getId(),
                command.reporterUserId(),
                command.reason(),
                command.description(),
                comment.getBody(),
                createdAt
        );

        return reportRepositoryPort.save(report);
    }
}
