package com.universe.interaction.application.ports;

import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for persisting and querying {@link InteractionReport} domain aggregates.
 */
public interface InteractionReportRepositoryPort {

    /**
     * Saves or updates an {@link InteractionReport} aggregate.
     *
     * @param report the domain aggregate to save
     * @return the saved domain aggregate
     */
    InteractionReport save(InteractionReport report);

    /**
     * Finds an {@link InteractionReport} aggregate by its unique ID.
     *
     * @param id the unique report UUID
     * @return an Optional containing the domain report if found
     */
    Optional<InteractionReport> findById(UUID id);

    /**
     * Target metadata projection for an interaction report.
     */
    record ReportTargetMetadata(ReportTargetType targetType, UUID targetId) {}

    /**
     * Retrieves the scalar target metadata (target type and target ID) for a report without loading the aggregate.
     *
     * @param id the unique report UUID
     * @return an Optional containing the target metadata if found
     */
    default Optional<ReportTargetMetadata> findTargetMetadataById(UUID id) {
        return findById(id).map(r -> new ReportTargetMetadata(r.getTargetType(), r.getTargetId()));
    }

    /**
     * Retrieves the scalar target ID for a report without loading the aggregate.
     *
     * @param id the unique report UUID
     * @return an Optional containing the scalar target UUID if found
     */
    default Optional<UUID> findTargetIdById(UUID id) {
        return findTargetMetadataById(id).map(ReportTargetMetadata::targetId);
    }

    /**
     * Checks whether an active PENDING report already exists for the given target and reporter.
     *
     * @param targetType the target type
     * @param targetId the target ID
     * @param reporterUserId the ID of the reporting user
     * @return true if a PENDING report already exists for this pair
     */
    boolean existsPendingByTargetAndReporter(ReportTargetType targetType, UUID targetId, UUID reporterUserId);

    /**
     * Checks whether any active PENDING report exists for the given target.
     *
     * @param targetType the target type
     * @param targetId the target ID
     * @return true if at least one PENDING report exists for this target
     */
    boolean existsPendingByTarget(ReportTargetType targetType, UUID targetId);

    /**
     * Backward-compatible alias checking whether a PENDING report exists for a comment and reporter.
     */
    default boolean existsPendingByCommentIdAndReporterUserId(UUID commentId, UUID reporterUserId) {
        return existsPendingByTargetAndReporter(ReportTargetType.COMMENT, commentId, reporterUserId);
    }

    /**
     * Finds an {@link InteractionReport} aggregate by its unique ID with an exclusive pessimistic write lock
     * (SELECT ... FOR UPDATE).
     *
     * <p>Callers must invoke this method within an active transaction to protect against concurrent resolution races.
     *
     * @param reportId the unique report UUID
     * @return an Optional containing the locked domain report if found, empty otherwise
     */
    Optional<InteractionReport> findByIdForUpdate(UUID reportId);

    /**
     * Stamps {@code targetDeletedAt} on all reports for the given targets where {@code targetDeletedAt} is currently null.
     *
     * @param targetType the target type
     * @param targetIds the collection of target UUIDs
     * @param targetDeletedAt the timestamp of physical target deletion
     * @return number of stamped reports
     */
    int stampTargetDeletedAtForTargets(ReportTargetType targetType, Collection<UUID> targetIds, Instant targetDeletedAt);

    /**
     * Physically purges expired reports according to hierarchical retention rules:
     * 1. If target_deleted_at IS NOT NULL: eligible if target_deleted_at < cutoff
     * 2. Else if terminal status: eligible if resolved_at < cutoff
     * 3. Else (PENDING with active target): never eligible.
     *
     * @param cutoff the strict cutoff timestamp
     * @param limit maximum number of reports to delete in this batch
     * @return the number of deleted report records
     */
    int purgeExpiredReportsBefore(Instant cutoff, int limit);

    /**
     * Backward-compatible alias for {@link #purgeExpiredReportsBefore(Instant, int)}.
     */
    default int purgeExpiredResolvedBefore(Instant cutoff, int limit) {
        return purgeExpiredReportsBefore(cutoff, limit);
    }

    /**
     * Counts reports for the specified target type and IDs where target_deleted_at IS NULL.
     *
     * @param targetType the target type
     * @param targetIds collection of target IDs
     * @return count of unstamped reports
     */
    long countUnstampedReportsByTargets(ReportTargetType targetType, Collection<UUID> targetIds);

    /**
     * Immutable page projection for interaction reports.
     */
    record InteractionReportPage(
            List<InteractionReport> items,
            int page,
            int size,
            long totalElements
    ) {}

    /**
     * Finds a paginated page of community post reports matching the specified filter criteria.
     */
    InteractionReportPage findCommunityPostReports(
            ReportStatus status,
            ReportReason reason,
            boolean oldestFirst,
            int page,
            int size
    );
}
