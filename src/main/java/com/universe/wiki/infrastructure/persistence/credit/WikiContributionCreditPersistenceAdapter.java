package com.universe.wiki.infrastructure.persistence.credit;

import com.universe.wiki.application.exceptions.WikiContributionAlreadyCreditedException;
import com.universe.wiki.application.exceptions.WikiContributionCreditStaleMutationException;
import com.universe.wiki.application.ports.WikiContributionCreditRepositoryPort;
import com.universe.wiki.domain.credit.CreditStatus;
import com.universe.wiki.domain.credit.WikiContributionCredit;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter hiện thực WikiContributionCreditRepositoryPort.
 *
 * Chịu trách nhiệm:
 * 1. Chuyển đổi hai chiều giữa Aggregate WikiContributionCredit và WikiContributionCreditJpaEntity;
 * 2. Bảo đảm ràng buộc duy nhất uq_wiki_contribution_credits_contribution là thẩm quyền race authority tối hậu;
 * 3. Chuyển ngữ biệt lệ vi phạm ràng buộc duy nhất thành WikiContributionAlreadyCreditedException;
 * 4. Quản lý khóa lạc quan qua JPA @Version, bắt ObjectOptimisticLockingFailureException và chuyển thành WikiContributionCreditStaleMutationException.
 */
@Component
public class WikiContributionCreditPersistenceAdapter implements WikiContributionCreditRepositoryPort {

    private static final String TARGET_UNIQUE_CONSTRAINT = "uq_wiki_contribution_credits_contribution";

    private final WikiContributionCreditSpringDataRepository repository;

    public WikiContributionCreditPersistenceAdapter(WikiContributionCreditSpringDataRepository repository) {
        this.repository = Objects.requireNonNull(
                repository,
                "WikiContributionCreditSpringDataRepository không được để trống."
        );
    }

    @Override
    public Optional<WikiContributionCredit> findByContributionId(UUID contributionId) {
        if (contributionId == null) {
            return Optional.empty();
        }
        return repository.findByContributionId(contributionId.toString())
                .map(this::toDomain);
    }

    @Override
    public WikiContributionCredit save(WikiContributionCredit credit) {
        if (credit == null) {
            throw new IllegalArgumentException("WikiContributionCredit không được để trống.");
        }

        String creditIdStr = credit.getId().toString();
        Optional<WikiContributionCreditJpaEntity> existingOpt = repository.findById(creditIdStr);

        WikiContributionCreditJpaEntity entity;
        if (existingOpt.isPresent()) {
            entity = existingOpt.get();
            mapToExistingEntity(credit, entity);
        } else {
            entity = new WikiContributionCreditJpaEntity();
            entity.setId(creditIdStr);
            mapToNewEntity(credit, entity);
        }

        try {
            WikiContributionCreditJpaEntity savedEntity = repository.saveAndFlush(entity);
            return toDomain(savedEntity);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
            throw new WikiContributionCreditStaleMutationException(credit.getId(), ex);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateConstraintViolation(ex)) {
                throw new WikiContributionAlreadyCreditedException(credit.getContributionId(), ex);
            }
            throw ex;
        }
    }

    private void mapToNewEntity(WikiContributionCredit credit, WikiContributionCreditJpaEntity entity) {
        entity.setContributionId(credit.getContributionId().toString());
        entity.setCreditStatus(credit.getStatus().name());
        entity.setCreditedByUserId(credit.getCreditedByUserId().toString());
        entity.setCreditedAt(credit.getCreditedAt());
        entity.setCreditNote(credit.getCreditNote());
        entity.setRevokedByUserId(credit.getRevokedByUserId() != null ? credit.getRevokedByUserId().toString() : null);
        entity.setRevokedAt(credit.getRevokedAt());
        entity.setRevocationReason(credit.getRevocationReason());
    }

    private void mapToExistingEntity(WikiContributionCredit credit, WikiContributionCreditJpaEntity entity) {
        entity.setCreditStatus(credit.getStatus().name());
        entity.setRevokedByUserId(credit.getRevokedByUserId() != null ? credit.getRevokedByUserId().toString() : null);
        entity.setRevokedAt(credit.getRevokedAt());
        entity.setRevocationReason(credit.getRevocationReason());
    }

    private WikiContributionCredit toDomain(WikiContributionCreditJpaEntity entity) {
        return WikiContributionCredit.reconstitute(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getContributionId()),
                CreditStatus.valueOf(entity.getCreditStatus()),
                UUID.fromString(entity.getCreditedByUserId()),
                entity.getCreditedAt(),
                entity.getCreditNote(),
                entity.getRevokedByUserId() != null ? UUID.fromString(entity.getRevokedByUserId()) : null,
                entity.getRevokedAt(),
                entity.getRevocationReason()
        );
    }

    private boolean isDuplicateConstraintViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null
                        && cve.getConstraintName().toLowerCase().contains(TARGET_UNIQUE_CONSTRAINT)) {
                    return true;
                }
            }
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(TARGET_UNIQUE_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
