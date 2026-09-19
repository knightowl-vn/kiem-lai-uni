package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link InteractionReportRepositoryPort} using Spring Data JPA.
 */
@Component
@Transactional(readOnly = true)
public class InteractionReportPersistenceAdapter implements InteractionReportRepositoryPort {

    private final SpringDataInteractionReportRepository repository;
    private final InteractionReportPersistenceMapper mapper;

    public InteractionReportPersistenceAdapter(
            SpringDataInteractionReportRepository repository,
            InteractionReportPersistenceMapper mapper
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataInteractionReportRepository cannot be null.");
        this.mapper = Objects.requireNonNull(mapper, "InteractionReportPersistenceMapper cannot be null.");
    }

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
                        report.getCommentId(),
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
                if (cve.getConstraintName() != null
                        && cve.getConstraintName().toLowerCase().contains(UQ_PENDING_REPORTER)) {
                    return true;
                }
            }
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(UQ_PENDING_REPORTER)) {
                return true;
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
    public boolean existsPendingByCommentIdAndReporterUserId(UUID commentId, UUID reporterUserId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        if (reporterUserId == null) {
            throw new IllegalArgumentException("Reporter user ID cannot be null.");
        }
        return repository.existsByCommentIdAndReporterUserIdAndStatus(
                commentId.toString(),
                reporterUserId.toString(),
                ReportStatus.PENDING.name()
        );
    }
}
