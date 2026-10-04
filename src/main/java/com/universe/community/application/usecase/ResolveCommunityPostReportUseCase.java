package com.universe.community.application.usecase;

import com.universe.community.application.command.ResolveCommunityPostReportCommand;
import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
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
import java.util.UUID;

/**
 * Atomic application use case for resolving reports filed against Community Posts.
 *
 * <p>Orchestrates cross-context interaction and post lifecycle rules under a single transaction boundary:
 * <ul>
 *   <li>For {@link ReportModerationAction#NO_ACTION}:
 *     resolves the report as {@code RESOLVED_NO_ACTION}. Does NOT alter Community post status or record post events.
 *   </li>
 *   <li>For {@link ReportModerationAction#CONTENT_HIDDEN}:
 *     locks target Community post first (global Post -> Report lock ordering), validates post is {@code PUBLISHED},
 *     transitions post to {@code HIDDEN}, persists post, appends {@link CommunityPostModerationEvent}, and resolves
 *     the report as {@code RESOLVED_ACTION_TAKEN} with action {@code CONTENT_HIDDEN}.
 *   </li>
 * </ul>
 */
@Service
public class ResolveCommunityPostReportUseCase {

    private final InteractionReportRepositoryPort reportRepositoryPort;
    private final CommunityPostRepositoryPort postRepositoryPort;
    private final CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;
    private final ClockPort clockPort;

    public ResolveCommunityPostReportUseCase(
            InteractionReportRepositoryPort reportRepositoryPort,
            CommunityPostRepositoryPort postRepositoryPort,
            CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort,
            ClockPort clockPort
    ) {
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "reportRepositoryPort cannot be null.");
        this.postRepositoryPort = Objects.requireNonNull(postRepositoryPort, "postRepositoryPort cannot be null.");
        this.moderationEventRepositoryPort = Objects.requireNonNull(moderationEventRepositoryPort, "moderationEventRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort cannot be null.");
    }

    @Transactional
    public void execute(ResolveCommunityPostReportCommand command) {
        Objects.requireNonNull(command, "ResolveCommunityPostReportCommand cannot be null.");

        switch (command.action()) {
            case NO_ACTION -> {
                InteractionReport report = reportRepositoryPort.findByIdForUpdate(command.reportId())
                        .orElseThrow(() -> new InteractionReportNotFoundException(command.reportId()));

                if (!report.isPending()) {
                    throw new ReportAlreadyResolvedException(report.getId(), report.getStatus());
                }

                Instant now = clockPort.now();
                report.resolveNoAction(command.moderatorUserId(), now);
                reportRepositoryPort.save(report);
            }
            case CONTENT_HIDDEN -> {
                // 1. Discover target report metadata without locking
                InteractionReportRepositoryPort.ReportTargetMetadata targetMetadata = reportRepositoryPort
                        .findTargetMetadataById(command.reportId())
                        .orElseThrow(() -> new InteractionReportNotFoundException(command.reportId()));

                if (targetMetadata.targetType() != ReportTargetType.COMMUNITY_POST) {
                    throw new UnsupportedReportModerationActionException(
                            targetMetadata.targetType(),
                            command.action()
                    );
                }

                UUID postId = targetMetadata.targetId();

                // 2. Lock target Community post FIRST (consistent global lock order: Post -> Report)
                CommunityPost post = postRepositoryPort.findByIdForUpdate(postId)
                        .orElseThrow(() -> new CommunityPostNotFoundException(postId));

                if (post.getStatus() != CommunityPostStatus.PUBLISHED) {
                    throw new IllegalStateException("Cannot hide post with status: " + post.getStatus());
                }

                // 3. Lock report SECOND with pessimistic write lock
                InteractionReport report = reportRepositoryPort.findByIdForUpdate(command.reportId())
                        .orElseThrow(() -> new InteractionReportNotFoundException(command.reportId()));

                if (!report.isPending()) {
                    throw new ReportAlreadyResolvedException(report.getId(), report.getStatus());
                }

                Instant now = clockPort.now();
                post.hide(now);
                postRepositoryPort.save(post);

                CommunityPostModerationEvent event = new CommunityPostModerationEvent(
                        UUID.randomUUID(),
                        postId,
                        CommunityPostModerationAction.HIDE,
                        CommunityPostStatus.PUBLISHED,
                        CommunityPostStatus.HIDDEN,
                        command.moderatorUserId(),
                        command.reason(),
                        now
                );
                moderationEventRepositoryPort.save(event);

                report.resolveContentHidden(command.moderatorUserId(), now);
                reportRepositoryPort.save(report);
            }
            default -> throw new IllegalArgumentException("Unsupported moderation action for community post: " + command.action());
        }
    }
}
