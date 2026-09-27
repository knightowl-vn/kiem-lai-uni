package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.InteractionReportQueueQueryPort;
import com.universe.interaction.application.query.InteractionReportQueueFilter;
import com.universe.interaction.application.query.InteractionReportQueueItem;
import com.universe.interaction.application.query.InteractionReportQueuePage;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link InteractionReportQueueQueryPort} using Spring Data JPA.
 *
 * <p>Executes native joined queries against {@code interaction_reports} and {@code interaction_comments}
 * without hydrating entity graphs or coupling cross-context modules.
 */
@Component
@Transactional(readOnly = true)
public class InteractionReportQueuePersistenceAdapter implements InteractionReportQueueQueryPort {

    private final SpringDataInteractionReportRepository repository;

    public InteractionReportQueuePersistenceAdapter(SpringDataInteractionReportRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataInteractionReportRepository cannot be null.");
    }

    @Override
    public InteractionReportQueuePage findQueueReports(InteractionReportQueueFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("InteractionReportQueueFilter cannot be null.");
        }

        String reasonParam = filter.reason() != null ? filter.reason().name() : null;
        String targetTypeParam = filter.targetType() != null ? filter.targetType().name() : null;

        // Unsorted PageRequest because SQL query explicitly owns the ORDER BY
        Pageable pageable = PageRequest.of(filter.page(), filter.size());

        Page<InteractionReportQueueRowProjection> springPage = switch (filter.scope()) {
            case PENDING -> switch (filter.sort()) {
                case NEWEST -> repository.findPendingQueueNewest(reasonParam, targetTypeParam, pageable);
                case OLDEST -> repository.findPendingQueueOldest(reasonParam, targetTypeParam, pageable);
            };
            case PROCESSED -> repository.findProcessedQueue(reasonParam, targetTypeParam, pageable);
        };

        List<InteractionReportQueueItem> items = springPage.getContent().stream()
                .map(this::toItem)
                .toList();

        return new InteractionReportQueuePage(
                items,
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements()
        );
    }

    private InteractionReportQueueItem toItem(InteractionReportQueueRowProjection row) {
        if (row == null) {
            throw new IllegalStateException("Queue projection row cannot be null.");
        }

        UUID reportId = parseUuid(row.getReportId(), "reportId");
        UUID commentId = parseUuid(row.getCommentId(), "commentId");
        UUID reporterUserId = parseUuid(row.getReporterUserId(), "reporterUserId");
        ReportReason reason = parseReason(row.getReason());
        String description = row.getDescription(); // nullable
        String reportedBodySnapshot = requireNonBlank(row.getReportedBodySnapshot(), "reportedBodySnapshot");
        ReportStatus status = parseStatus(row.getReportStatus());
        Instant createdAt = Objects.requireNonNull(row.getCreatedAt(), "createdAt cannot be null.");

        UUID commentAuthorUserId = parseUuid(row.getCommentAuthorUserId(), "commentAuthorUserId");
        CommentTargetType targetType = parseTargetType(row.getTargetType());
        UUID targetId = parseUuid(row.getTargetId(), "targetId");
        CommentStatus commentStatus = parseCommentStatus(row.getCommentStatus());

        ReportModerationAction moderationAction = parseModerationAction(row.getModerationAction());
        UUID resolverUserId = row.getResolvedByUserId() != null
                ? parseUuid(row.getResolvedByUserId(), "resolvedByUserId")
                : null;
        Instant resolvedAt = row.getResolvedAt();

        return new InteractionReportQueueItem(
                reportId,
                commentId,
                reporterUserId,
                reason,
                description,
                reportedBodySnapshot,
                status,
                createdAt,
                commentAuthorUserId,
                targetType,
                targetId,
                commentStatus,
                moderationAction,
                resolverUserId,
                resolvedAt
        );
    }

    private static UUID parseUuid(String value, String fieldName) {
        if (value == null) {
            throw new IllegalStateException(fieldName + " cannot be null in persisted queue row.");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid UUID format for " + fieldName + ": " + value, ex);
        }
    }

    private static ReportReason parseReason(String value) {
        if (value == null) {
            throw new IllegalStateException("reason cannot be null in persisted queue row.");
        }
        try {
            return ReportReason.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid ReportReason: " + value, ex);
        }
    }

    private static ReportStatus parseStatus(String value) {
        if (value == null) {
            throw new IllegalStateException("reportStatus cannot be null in persisted queue row.");
        }
        try {
            return ReportStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid ReportStatus: " + value, ex);
        }
    }

    private static CommentTargetType parseTargetType(String value) {
        if (value == null) {
            throw new IllegalStateException("targetType cannot be null in persisted queue row.");
        }
        try {
            return CommentTargetType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid CommentTargetType: " + value, ex);
        }
    }

    private static CommentStatus parseCommentStatus(String value) {
        if (value == null) {
            throw new IllegalStateException("commentStatus cannot be null in persisted queue row.");
        }
        try {
            return CommentStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid CommentStatus: " + value, ex);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(fieldName + " cannot be null or blank in persisted queue row.");
        }
        return value;
    }

    private static ReportModerationAction parseModerationAction(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ReportModerationAction.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid ReportModerationAction: " + value, ex);
        }
    }
}
