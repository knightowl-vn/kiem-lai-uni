package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import com.universe.screen.application.exceptions.ScreenFranchiseSlugAlreadyExistsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class ScreenFranchisePersistenceAdapter implements ScreenFranchiseRepositoryPort {

    private static final String TARGET_UNIQUE_CONSTRAINT = "uq_screen_franchises_slug";
    private static final java.util.regex.Pattern CONSTRAINT_PATTERN = java.util.regex.Pattern.compile(
            "(^|[^a-zA-Z0-9_])" + java.util.regex.Pattern.quote(TARGET_UNIQUE_CONSTRAINT) + "([^a-zA-Z0-9_]|$)",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private final SpringDataScreenFranchiseJpaRepository repository;

    public ScreenFranchisePersistenceAdapter(SpringDataScreenFranchiseJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataScreenFranchiseJpaRepository cannot be null.");
    }

    @Override
    @Transactional
    public ScreenFranchise save(ScreenFranchise franchise) {
        Objects.requireNonNull(franchise, "ScreenFranchise cannot be null.");

        String id = franchise.getId().toString();
        ScreenFranchiseJpaEntity entity = repository.findById(id)
                .map(existing -> {
                    ScreenFranchisePersistenceMapper.updateEntity(existing, franchise);
                    return existing;
                })
                .orElseGet(() -> ScreenFranchisePersistenceMapper.toEntity(franchise));

        try {
            ScreenFranchiseJpaEntity saved = repository.saveAndFlush(entity);
            return ScreenFranchisePersistenceMapper.toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isSlugConstraintViolation(ex)) {
                throw new ScreenFranchiseSlugAlreadyExistsException(franchise.getSlug(), ex);
            }
            throw ex;
        }
    }

    private boolean isSlugConstraintViolation(DataIntegrityViolationException ex) {
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
    public Optional<ScreenFranchise> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(ScreenFranchisePersistenceMapper::toDomain);
    }

    @Override
    public Optional<ScreenFranchise> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return repository.findBySlug(slug.trim().toLowerCase(Locale.ROOT))
                .map(ScreenFranchisePersistenceMapper::toDomain);
    }

    @Override
    public List<ScreenFranchise> findAll() {
        return repository.findAllOrderByNameAsc()
                .stream()
                .map(ScreenFranchisePersistenceMapper::toDomain)
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
    public boolean existsBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        return repository.existsBySlug(slug.trim().toLowerCase(Locale.ROOT));
    }
}
