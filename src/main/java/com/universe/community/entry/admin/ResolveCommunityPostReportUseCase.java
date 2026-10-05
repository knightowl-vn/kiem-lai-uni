package com.universe.community.entry.admin;

import com.universe.community.application.command.HideCommunityPostCommand;
import com.universe.community.application.usecase.HideCommunityPostUseCase;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.exceptions.UnsupportedReportModerationActionException;
import com.universe.interaction.application.mutation.ResolveInteractionReportCommand;
import com.universe.interaction.application.mutation.ResolveInteractionReportUseCase;
import com.universe.interaction.application.query.GetInteractionReportDetailUseCase;
import com.universe.interaction.application.query.InteractionReportDetailResult;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context composition coordinator for resolving Community Post interaction reports.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Boundary integrity: Community application has zero Interaction imports;</li>
 *   <li>Ownership integrity: Interaction owns report resolution, Community owns post lifecycle;</li>
 *   <li>No cross-context repository dependencies (depends only on application use cases);</li>
 *   <li>Consistent global lock ordering: Community Post lock FIRST, Interaction Report lock SECOND;</li>
 *   <li>Atomic execution: both mutations participate in the same database transaction.</li>
 * </ul>
 */
@Service
public class ResolveCommunityPostReportUseCase {

    private final GetInteractionReportDetailUseCase getInteractionReportDetailUseCase;
    private final HideCommunityPostUseCase hideCommunityPostUseCase;
    private final ResolveInteractionReportUseCase resolveInteractionReportUseCase;

    public ResolveCommunityPostReportUseCase(
            GetInteractionReportDetailUseCase getInteractionReportDetailUseCase,
            HideCommunityPostUseCase hideCommunityPostUseCase,
            ResolveInteractionReportUseCase resolveInteractionReportUseCase
    ) {
        this.getInteractionReportDetailUseCase = Objects.requireNonNull(
                getInteractionReportDetailUseCase, "getInteractionReportDetailUseCase cannot be null."
        );
        this.hideCommunityPostUseCase = Objects.requireNonNull(
                hideCommunityPostUseCase, "hideCommunityPostUseCase cannot be null."
        );
        this.resolveInteractionReportUseCase = Objects.requireNonNull(
                resolveInteractionReportUseCase, "resolveInteractionReportUseCase cannot be null."
        );
    }

    @Transactional
    public void execute(ResolveCommunityPostReportCommand command) {
        Objects.requireNonNull(command, "ResolveCommunityPostReportCommand cannot be null.");

        switch (command.action()) {
            case NO_ACTION -> {
                resolveInteractionReportUseCase.execute(new ResolveInteractionReportCommand(
                        command.reportId(),
                        command.moderatorUserId(),
                        ReportModerationAction.NO_ACTION
                ));
            }
            case CONTENT_HIDDEN -> {
                // 1. Discover target report metadata without locking
                InteractionReportDetailResult detail = getInteractionReportDetailUseCase.execute(command.reportId());

                if (detail.reportTargetType() != ReportTargetType.COMMUNITY_POST) {
                    throw new UnsupportedReportModerationActionException(
                            detail.reportTargetType(),
                            command.action()
                    );
                }

                if (detail.status() != ReportStatus.PENDING) {
                    throw new ReportAlreadyResolvedException(detail.reportId(), detail.status());
                }

                UUID postId = detail.reportTargetId();

                // 2. Lock target Community post FIRST (consistent global lock order: Post -> Report)
                hideCommunityPostUseCase.execute(new HideCommunityPostCommand(
                        postId,
                        command.moderatorUserId(),
                        command.reason()
                ));

                // 3. Lock report SECOND and resolve report
                resolveInteractionReportUseCase.execute(new ResolveInteractionReportCommand(
                        command.reportId(),
                        command.moderatorUserId(),
                        ReportModerationAction.CONTENT_HIDDEN
                ));
            }
            default -> throw new IllegalArgumentException("Unsupported moderation action for community post: " + command.action());
        }
    }
}
