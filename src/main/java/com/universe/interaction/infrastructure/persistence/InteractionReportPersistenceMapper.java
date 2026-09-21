package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Persistence mapper converting between domain {@link InteractionReport} and {@link InteractionReportJpaEntity}.
 *
 * <p>Preserves all domain invariants during round-trip:
 * <ul>
 *   <li>Exact scalar UUID &harr; CHAR(36) String conversion;</li>
 *   <li>ReportReason and ReportStatus name preservation;</li>
 *   <li>Snapshot evidence immutability;</li>
 *   <li>Nullable description and resolution fields;</li>
 *   <li>Authoritative moderation action persistence;</li>
 *   <li>Reconstitution via {@link InteractionReport#reconstitute}.</li>
 * </ul>
 */
@Component
public class InteractionReportPersistenceMapper {

    /**
     * Maps a domain {@link InteractionReport} aggregate to a {@link InteractionReportJpaEntity}.
     */
    public InteractionReportJpaEntity toJpaEntity(InteractionReport domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain report cannot be null.");
        }

        return new InteractionReportJpaEntity(
                domain.getId().toString(),
                domain.getCommentId().toString(),
                domain.getReporterUserId().toString(),
                domain.getReason().name(),
                domain.getDescription(),
                domain.getReportedBodySnapshot(),
                domain.getStatus().name(),
                domain.getCreatedAt(),
                domain.getResolvedByUserId() != null ? domain.getResolvedByUserId().toString() : null,
                domain.getResolvedAt(),
                domain.getModerationAction() != null ? domain.getModerationAction().name() : null
        );
    }

    /**
     * Reconstitutes a domain {@link InteractionReport} from a {@link InteractionReportJpaEntity}.
     */
    public InteractionReport toDomain(InteractionReportJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("InteractionReportJpaEntity cannot be null.");
        }

        UUID id = parseUuid(entity.getId(), "Report ID");
        UUID commentId = parseUuid(entity.getCommentId(), "Comment ID");
        UUID reporterUserId = parseUuid(entity.getReporterUserId(), "Reporter user ID");
        ReportReason reason = parseReason(entity.getReason());
        ReportStatus status = parseStatus(entity.getStatus());
        UUID resolvedByUserId = entity.getResolvedByUserId() != null
                ? parseUuid(entity.getResolvedByUserId(), "ResolvedBy user ID")
                : null;
        ReportModerationAction moderationAction = parseModerationAction(entity.getModerationAction());

        return InteractionReport.reconstitute(
                id,
                commentId,
                reporterUserId,
                reason,
                entity.getDescription(),
                entity.getReportedBodySnapshot(),
                status,
                entity.getCreatedAt(),
                resolvedByUserId,
                entity.getResolvedAt(),
                moderationAction
        );
    }

    private static UUID parseUuid(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null.");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid UUID format for " + fieldName + ": " + value, ex);
        }
    }

    private static ReportReason parseReason(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Report reason cannot be null.");
        }
        try {
            return ReportReason.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown report reason: " + value, ex);
        }
    }

    private static ReportStatus parseStatus(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Report status cannot be null.");
        }
        try {
            return ReportStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown report status: " + value, ex);
        }
    }

    private static ReportModerationAction parseModerationAction(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ReportModerationAction.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown report moderation action: " + value, ex);
        }
    }
}
