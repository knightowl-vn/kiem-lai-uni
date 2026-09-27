package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.report.InteractionReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case to retrieve raw, consumer-neutral details for a single interaction report.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Authoritative report retrieval via {@link InteractionReportRepositoryPort};</li>
 *   <li>Missing report throws {@link InteractionReportNotFoundException};</li>
 *   <li>Current comment lookup via {@link CommentRepositoryPort} using immutable {@code report.commentId};</li>
 *   <li>Missing current comment does NOT fail the query; historical report evidence remains accessible;</li>
 *   <li>Soft-deleted comments are treated as available tombstones with null current body;</li>
 *   <li>Zero cross-context imports; consumer-neutral application boundary.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class GetInteractionReportDetailUseCase {

    private final InteractionReportRepositoryPort reportRepositoryPort;
    private final CommentRepositoryPort commentRepositoryPort;

    public GetInteractionReportDetailUseCase(
            InteractionReportRepositoryPort reportRepositoryPort,
            CommentRepositoryPort commentRepositoryPort
    ) {
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "InteractionReportRepositoryPort cannot be null.");
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
    }

    /**
     * Executes retrieval of the raw detail for a single comment report.
     *
     * @param reportId unique report ID (cannot be null)
     * @return immutable {@link InteractionReportDetailResult}
     * @throws IllegalArgumentException if reportId is null
     * @throws InteractionReportNotFoundException if no report exists for reportId
     */
    public InteractionReportDetailResult execute(UUID reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("Report ID cannot be null.");
        }

        InteractionReport report = reportRepositoryPort.findById(reportId)
                .orElseThrow(() -> new InteractionReportNotFoundException(reportId));

        Optional<Comment> commentOptional = commentRepositoryPort.findById(report.getCommentId());

        return commentOptional
                .map(comment -> InteractionReportDetailResult.withAvailableComment(report, comment))
                .orElseGet(() -> InteractionReportDetailResult.withMissingComment(report));
    }
}
