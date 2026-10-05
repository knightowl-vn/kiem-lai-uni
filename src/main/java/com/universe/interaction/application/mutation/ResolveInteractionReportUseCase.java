package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.exceptions.UnsupportedReportModerationActionException;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Atomic application use case for resolving interaction reports.
 *
 * <p>Preserves clean architecture boundaries:
 * <ul>
 *   <li>The use case orchestrates the report state mutation within a transaction;</li>
 *   <li>Guards against terminal report status, throwing {@link ReportAlreadyResolvedException};</li>
 *   <li>For {@link ReportModerationAction#NO_ACTION}:
 *     resolves the report as {@code RESOLVED_NO_ACTION};</li>
 *   <li>For {@link ReportModerationAction#CONTENT_HIDDEN}:
 *     validates target is {@link ReportTargetType#COMMUNITY_POST},
 *     transitions report to {@code RESOLVED_ACTION_TAKEN} with action {@code CONTENT_HIDDEN};</li>
 * </ul>
 */
@Service
@Transactional
public class ResolveInteractionReportUseCase {

    private final InteractionReportRepositoryPort reportRepositoryPort;
    private final ClockPort clockPort;

    public ResolveInteractionReportUseCase(
            InteractionReportRepositoryPort reportRepositoryPort,
            ClockPort clockPort
    ) {
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "reportRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort cannot be null.");
    }

    public void execute(ResolveInteractionReportCommand command) {
        Objects.requireNonNull(command, "ResolveInteractionReportCommand cannot be null.");

        InteractionReport report = reportRepositoryPort.findByIdForUpdate(command.reportId())
                .orElseThrow(() -> new InteractionReportNotFoundException(command.reportId()));

        if (!report.isPending()) {
            throw new ReportAlreadyResolvedException(report.getId(), report.getStatus());
        }

        Instant now = clockPort.now();
        switch (command.action()) {
            case NO_ACTION -> {
                report.resolveNoAction(command.moderatorUserId(), now);
                reportRepositoryPort.save(report);
            }
            case CONTENT_HIDDEN -> {
                if (report.getTargetType() != ReportTargetType.COMMUNITY_POST) {
                    throw new UnsupportedReportModerationActionException(
                            report.getTargetType(),
                            command.action()
                    );
                }
                report.resolveContentHidden(command.moderatorUserId(), now);
                reportRepositoryPort.save(report);
            }
            default -> throw new UnsupportedReportModerationActionException(
                    report.getTargetType(),
                    command.action()
            );
        }
    }
}
