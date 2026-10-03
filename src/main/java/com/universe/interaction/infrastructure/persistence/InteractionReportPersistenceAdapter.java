package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Persistence adapter implementing {@link InteractionReportRepositoryPort} using Spring Data JPA.
 */
@Component
@Transactional(readOnly = true)
public class InteractionReportPersistenceAdapter implements InteractionReportRepositoryPort {

    private final SpringDataInteractionReportRepository repository;
    private final InteractionReportPersistenceMapper mapper;
    private final EntityManager entityManager;

    public InteractionReportPersistenceAdapter(
            SpringDataInteractionReportRepository repository,
            InteractionReportPersistenceMapper mapper,
            EntityManager entityManager
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataInteractionReportRepository cannot be null.");
        this.mapper = Objects.requireNonNull(mapper, "InteractionReportPersistenceMapper cannot be null.");
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager cannot be null.");
    }

    private static final String UQ_PENDING_TARGET_REPORTER = "uq_interaction_reports_pending_target_reporter";
    private static final String UQ_PENDING_REPORTER = "uq_interaction_reports_pending_reporter";

    @Override
    @Transactional
    public InteractionReport save(InteractionReport report) {
        if (report == null) {
            throw new IllegalArgumentException("InteractionReport cannot be null.");
        }
        InteractionReportJpaEntity entity = mapper.toJpaEntity(report);
        try {
            InteractionReportJpaEntity savedEntity = repository.saveAndFlush(entity);
            return mapper.toDomain(savedEntity);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            if (isDuplicatePendingConstraintViolation(ex)) {
                throw new com.universe.interaction.application.exceptions.DuplicatePendingReportException(
                        report.getTargetType(),
                        report.getTargetId(),
                        report.getReporterUserId()
                );
            }
            throw ex;
        }
    }

    private boolean isDuplicatePendingConstraintViolation(org.springframework.dao.DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null) {
                    String constraint = cve.getConstraintName().toLowerCase();
                    if (constraint.contains(UQ_PENDING_TARGET_REPORTER) || constraint.contains(UQ_PENDING_REPORTER)) {
                        return true;
                    }
                }
            }
            if (current.getMessage() != null) {
                String msg = current.getMessage().toLowerCase();
                if (msg.contains(UQ_PENDING_TARGET_REPORTER) || msg.contains(UQ_PENDING_REPORTER)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public Optional<InteractionReport> findById(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Report ID cannot be null.");
        }
        return repository.findById(id.toString()).map(mapper::toDomain);
    }

    @Override
    public Optional<ReportTargetMetadata> findTargetMetadataById(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Report ID cannot be null.");
        }
        List<Object[]> rows = repository.findTargetMetadataById(id.toString());
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        String targetTypeStr = (String) row[0];
        String targetIdStr = (String) row[1];
        if (targetTypeStr == null || targetIdStr == null) {
            return Optional.empty();
        }
        return Optional.of(new ReportTargetMetadata(
                com.universe.interaction.domain.report.ReportTargetType.valueOf(targetTypeStr),
                UUID.fromString(targetIdStr)
        ));
    }

    @Override
    public Optional<UUID> findTargetIdById(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Report ID cannot be null.");
        }
        return repository.findTargetIdById(id.toString()).map(UUID::fromString);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<InteractionReport> findByIdForUpdate(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Report ID cannot be null.");
        }
        InteractionReportJpaEntity entity = entityManager.find(
                InteractionReportJpaEntity.class,
                id.toString(),
                LockModeType.PESSIMISTIC_WRITE
        );
        if (entity == null) {
            return Optional.empty();
        }
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(mapper.toDomain(entity));
    }

    @Override
    public boolean existsPendingByTargetAndReporter(
            com.universe.interaction.domain.report.ReportTargetType targetType,
            UUID targetId,
            UUID reporterUserId
    ) {
        if (targetType == null) {
            throw new IllegalArgumentException("ReportTargetType cannot be null.");
        }
        if (targetId == null) {
            throw new IllegalArgumentException("Target ID cannot be null.");
        }
        if (reporterUserId == null) {
            throw new IllegalArgumentException("Reporter user ID cannot be null.");
        }
        return repository.existsByTargetTypeAndTargetIdAndReporterUserIdAndStatus(
                targetType.name(),
                targetId.toString(),
                reporterUserId.toString(),
                ReportStatus.PENDING.name()
        );
    }

    @Override
    public boolean existsPendingByTarget(
            com.universe.interaction.domain.report.ReportTargetType targetType,
            UUID targetId
    ) {
        if (targetType == null) {
            throw new IllegalArgumentException("ReportTargetType cannot be null.");
        }
        if (targetId == null) {
            throw new IllegalArgumentException("Target ID cannot be null.");
        }
        return repository.existsByTargetTypeAndTargetIdAndStatus(
                targetType.name(),
                targetId.toString(),
                ReportStatus.PENDING.name()
        );
    }

    @Override
    public boolean existsPendingByCommentIdAndReporterUserId(UUID commentId, UUID reporterUserId) {
        return existsPendingByTargetAndReporter(
                com.universe.interaction.domain.report.ReportTargetType.COMMENT,
                commentId,
                reporterUserId
        );
    }

    @Override
    @Transactional
    public int stampTargetDeletedAtForTargets(
            com.universe.interaction.domain.report.ReportTargetType targetType,
            java.util.Collection<UUID> targetIds,
            Instant targetDeletedAt
    ) {
        if (targetType == null) {
            throw new IllegalArgumentException("ReportTargetType cannot be null.");
        }
        if (targetIds == null || targetIds.isEmpty()) {
            return 0;
        }
        if (targetDeletedAt == null) {
            throw new IllegalArgumentException("Target deleted at timestamp cannot be null.");
        }

        List<String> ids = targetIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }

        return repository.stampTargetDeletedAt(targetType.name(), ids, targetDeletedAt);
    }

    @Override
    @Transactional
    public int purgeExpiredReportsBefore(Instant cutoff, int limit) {
        if (cutoff == null) {
            throw new IllegalArgumentException("Cutoff cannot be null.");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be greater than 0, given: " + limit);
        }

        List<String> expiredIds = repository.findExpiredReportIds(cutoff, PageRequest.of(0, limit));
        if (expiredIds == null || expiredIds.isEmpty()) {
            return 0;
        }

        return repository.deleteByIdIn(expiredIds);
    }

    @Override
    @Transactional
    public int purgeExpiredResolvedBefore(Instant cutoff, int limit) {
        return purgeExpiredReportsBefore(cutoff, limit);
    }

    @Override
    public long countUnstampedReportsByTargets(
            com.universe.interaction.domain.report.ReportTargetType targetType,
            Collection<UUID> targetIds
    ) {
        if (targetType == null || targetIds == null || targetIds.isEmpty()) {
            return 0L;
        }
        List<String> ids = targetIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .toList();
        if (ids.isEmpty()) {
            return 0L;
        }
        return repository.countUnstampedReportsByTargets(targetType.name(), ids);
    }
}
