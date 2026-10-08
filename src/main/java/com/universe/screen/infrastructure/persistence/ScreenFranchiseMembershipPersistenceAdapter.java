package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.exceptions.DuplicateScreenFranchiseMembershipException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.domain.ScreenFranchiseMembership;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class ScreenFranchiseMembershipPersistenceAdapter implements ScreenFranchiseMembershipRepositoryPort {

    private static final String TARGET_UNIQUE_CONSTRAINT = "uq_screen_franchise_memberships_franchise_title";
    private static final java.util.regex.Pattern CONSTRAINT_PATTERN = java.util.regex.Pattern.compile(
            "(^|[^a-zA-Z0-9_])" + java.util.regex.Pattern.quote(TARGET_UNIQUE_CONSTRAINT) + "([^a-zA-Z0-9_]|$)",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private final SpringDataScreenFranchiseMembershipJpaRepository repository;

    public ScreenFranchiseMembershipPersistenceAdapter(SpringDataScreenFranchiseMembershipJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataScreenFranchiseMembershipJpaRepository cannot be null.");
    }

    @Override
    @Transactional
    public ScreenFranchiseMembership save(ScreenFranchiseMembership membership) {
        Objects.requireNonNull(membership, "ScreenFranchiseMembership cannot be null.");

        String id = membership.getId().toString();
        ScreenFranchiseMembershipJpaEntity entity = repository.findById(id)
                .map(existing -> {
                    ScreenFranchiseMembershipPersistenceMapper.updateEntity(existing, membership);
                    return existing;
                })
                .orElseGet(() -> ScreenFranchiseMembershipPersistenceMapper.toEntity(membership));

        try {
            ScreenFranchiseMembershipJpaEntity saved = repository.saveAndFlush(entity);
            return ScreenFranchiseMembershipPersistenceMapper.toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateConstraintViolation(ex)) {
                throw new DuplicateScreenFranchiseMembershipException(
                        membership.getFranchiseId(),
                        membership.getTitleId(),
                        ex
                );
            }
            throw ex;
        }
    }

    private boolean isDuplicateConstraintViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null && matchesTargetConstraint(cve.getConstraintName())) {
                    return true;
                }
            }
            if (current.getMessage() != null && matchesTargetConstraint(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean matchesTargetConstraint(String text) {
        return text != null && CONSTRAINT_PATTERN.matcher(text).find();
    }

    @Override
    public Optional<ScreenFranchiseMembership> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(ScreenFranchiseMembershipPersistenceMapper::toDomain);
    }

    @Override
    public Optional<ScreenFranchiseMembership> findByFranchiseIdAndTitleId(UUID franchiseId, UUID titleId) {
        if (franchiseId == null || titleId == null) {
            return Optional.empty();
        }
        return repository.findByFranchiseIdAndTitleId(franchiseId.toString(), titleId.toString())
                .map(ScreenFranchiseMembershipPersistenceMapper::toDomain);
    }

    @Override
    public List<ScreenFranchiseMembership> findByFranchiseId(UUID franchiseId) {
        if (franchiseId == null) {
            return Collections.emptyList();
        }
        return repository.findByFranchiseId(franchiseId.toString())
                .stream()
                .map(ScreenFranchiseMembershipPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public boolean existsById(UUID id) {
        if (id == null) {
            return false;
        }
        return repository.existsById(id.toString());
    }

    @Override
    public boolean existsByFranchiseIdAndTitleId(UUID franchiseId, UUID titleId) {
        if (franchiseId == null || titleId == null) {
            return false;
        }
        return repository.existsByFranchiseIdAndTitleId(franchiseId.toString(), titleId.toString());
    }

    @Override
    @Transactional
    public void delete(ScreenFranchiseMembership membership) {
        if (membership != null && membership.getId() != null) {
            repository.deleteById(membership.getId().toString());
        }
    }
}
