package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 *       <li>If present: physically removes comment, descendant replies, revisions, and reactions;</li>
 *       <li>If already deleted: treats deletion as idempotently satisfied without mutating comment;</li>
 *       <li>Transitions report to {@code RESOLVED_ACTION_TAKEN} and saves report;</li>
 *     </ul>
 *   </li>
 *   <li>For {@link ReportModerationAction#NO_ACTION}:
 *     <ul>
 *       <li>Does not interact with comment, reaction, or revision repositories;</li>
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
    private final ReactionRepositoryPort reactionRepositoryPort;
    private final ClockPort clockPort;

    public ResolveCommentReportUseCase(
            InteractionReportRepositoryPort reportRepositoryPort,
            CommentRepositoryPort commentRepositoryPort,
            CommentRevisionRepositoryPort commentRevisionRepositoryPort,
            ReactionRepositoryPort reactionRepositoryPort,
            ClockPort clockPort
    ) {
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "reportRepositoryPort cannot be null");
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "commentRepositoryPort cannot be null");
        this.commentRevisionRepositoryPort = Objects.requireNonNull(commentRevisionRepositoryPort, "commentRevisionRepositoryPort cannot be null");
        this.reactionRepositoryPort = Objects.requireNonNull(reactionRepositoryPort, "reactionRepositoryPort cannot be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort cannot be null");
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

        InteractionReport report = reportRepositoryPort.findByIdForUpdate(command.reportId())
                .orElseThrow(() -> new InteractionReportNotFoundException(command.reportId()));

        if (!report.isPending()) {
            throw new ReportAlreadyResolvedException(report.getId(), report.getStatus());
        }

        switch (command.action()) {
            case DELETE_COMMENT -> {
                Optional<Comment> commentOptional = commentRepositoryPort.findByIdForUpdate(report.getCommentId());
                if (commentOptional.isEmpty()) {
                    throw new CommentNotFoundException(report.getCommentId());
                }

                Comment comment = commentOptional.get();
                Set<UUID> commentIdsToDelete = new LinkedHashSet<>();
                commentIdsToDelete.add(comment.getId());

                if (comment.isRoot()) {
                    List<Comment> replies = commentRepositoryPort.findThreadReplies(comment.getId());
                    for (Comment reply : replies) {
                        commentIdsToDelete.add(reply.getId());
                    }
                } else {
                    List<Comment> replies = commentRepositoryPort.findThreadReplies(comment.getThreadRootCommentId());
                    boolean expanded = true;
                    while (expanded) {
                        expanded = false;
                        for (Comment r : replies) {
                            if (r.getParentCommentId() != null && commentIdsToDelete.contains(r.getParentCommentId())) {
                                if (commentIdsToDelete.add(r.getId())) {
                                    expanded = true;
                                }
                            }
                        }
                    }
                }

                reactionRepositoryPort.deleteAllByTargetIds(ReactionTargetType.COMMENT, commentIdsToDelete);
                commentRevisionRepositoryPort.deleteAllByCommentIds(commentIdsToDelete);
                commentRepositoryPort.deleteAllByIds(commentIdsToDelete);

                Instant now = clockPort.now();
                report.resolveActionTaken(command.moderatorUserId(), now);
                reportRepositoryPort.save(report);
            }
            case NO_ACTION -> {
                Instant now = clockPort.now();
                report.resolveNoAction(command.moderatorUserId(), now);
                reportRepositoryPort.save(report);
            }
        }
    }
}
