package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.report.InteractionReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Atomic application use case for resolving comment reports with moderation actions.
 *
 * <p>Preserves clean architecture boundaries and transaction boundaries:
 * <ul>
 *   <li>The use case orchestrates the entire moderation mutation within a single transaction;</li>
 *   <li>Pessimistically locks the report first via {@link InteractionReportRepositoryPort#findByIdForUpdate};</li>
 *   <li>Guards against terminal report status, throwing {@link ReportAlreadyResolvedException};</li>
 *   <li>For {@link ReportModerationAction#DELETE_COMMENT}:
 *     <ul>
 *       <li>Pessimistically locks the target comment via {@link CommentRepositoryPort#findByIdForUpdate};</li>
 *       <li>If active: soft-deletes comment, purges revisions, and persists updated comment;</li>
 *       <li>If already deleted: treats deletion as idempotently satisfied without mutating comment or revisions;</li>
 *       <li>Transitions report to {@code RESOLVED_ACTION_TAKEN} and saves report;</li>
 *     </ul>
 *   </li>
 *   <li>For {@link ReportModerationAction#NO_ACTION}:
 *     <ul>
 *       <li>Does not interact with comment or revision repositories;</li>
 *       <li>Transitions report to {@code RESOLVED_NO_ACTION} and saves report;</li>
 *     </ul>
 *   </li>
 *   <li>Sibling reports on the same comment remain untouched with independent lifecycles.</li>
 * </ul>
 */
@Service
@Transactional
public class ResolveCommentReportUseCase {

    private final InteractionReportRepositoryPort reportRepositoryPort;
    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentRevisionRepositoryPort commentRevisionRepositoryPort;
    private final Clock clock;

    public ResolveCommentReportUseCase(
            InteractionReportRepositoryPort reportRepositoryPort,
            CommentRepositoryPort commentRepositoryPort,
            CommentRevisionRepositoryPort commentRevisionRepositoryPort,
            Clock clock
    ) {
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "reportRepositoryPort cannot be null");
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "commentRepositoryPort cannot be null");
        this.commentRevisionRepositoryPort = Objects.requireNonNull(commentRevisionRepositoryPort, "commentRevisionRepositoryPort cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Executes the moderation action on the specified report.
     *
     * @param command the moderation command containing report ID, moderator ID, and action
     * @throws InteractionReportNotFoundException if the report does not exist
     * @throws ReportAlreadyResolvedException if the report is already in a terminal status
     * @throws CommentNotFoundException if DELETE_COMMENT is requested but the target comment is not found
     */
    public void execute(ResolveCommentReportCommand command) {
        Objects.requireNonNull(command, "ResolveCommentReportCommand cannot be null");

        Instant now = clock.instant();

        InteractionReport report = reportRepositoryPort.findByIdForUpdate(command.reportId())
                .orElseThrow(() -> new InteractionReportNotFoundException(command.reportId()));

        if (!report.isPending()) {
            throw new ReportAlreadyResolvedException(report.getId(), report.getStatus());
        }

        switch (command.action()) {
            case DELETE_COMMENT -> {
                Comment comment = commentRepositoryPort.findByIdForUpdate(report.getCommentId())
                        .orElseThrow(() -> new CommentNotFoundException(report.getCommentId()));

                if (comment.isActive()) {
                    comment.delete(now);
                    commentRevisionRepositoryPort.deleteAllByCommentId(comment.getId());
                    commentRepositoryPort.save(comment);
                }

                report.resolveActionTaken(command.moderatorUserId(), now);
                reportRepositoryPort.save(report);
            }
            case NO_ACTION -> {
                report.resolveNoAction(command.moderatorUserId(), now);
                reportRepositoryPort.save(report);
            }
        }
    }
}
