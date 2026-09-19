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

    @Override
    @Transactional
    public InteractionReport save(InteractionReport report) {
        if (report == null) {
            throw new IllegalArgumentException("InteractionReport cannot be null.");
        }
        InteractionReportJpaEntity entity = mapper.toJpaEntity(report);
        InteractionReportJpaEntity savedEntity = repository.saveAndFlush(entity);
        return mapper.toDomain(savedEntity);
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
