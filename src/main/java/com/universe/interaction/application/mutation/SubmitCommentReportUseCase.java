package com.universe.interaction.application.mutation;

import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Use case orchestrating submission of a user report against an interaction comment.
 * Delegates to {@link SubmitInteractionReportUseCase}.
 */
@Service
public class SubmitCommentReportUseCase {

    private final SubmitInteractionReportUseCase submitInteractionReportUseCase;

    public SubmitCommentReportUseCase(SubmitInteractionReportUseCase submitInteractionReportUseCase) {
        this.submitInteractionReportUseCase = Objects.requireNonNull(
                submitInteractionReportUseCase,
                "SubmitInteractionReportUseCase cannot be null."
        );
    }

    @Transactional
    public InteractionReport execute(SubmitCommentReportCommand command) {
        Objects.requireNonNull(command, "SubmitCommentReportCommand cannot be null.");

        return submitInteractionReportUseCase.execute(new SubmitInteractionReportCommand(
                ReportTargetType.COMMENT,
                command.commentId(),
                command.reporterUserId(),
                command.reason(),
                command.description()
        ));
    }
}
