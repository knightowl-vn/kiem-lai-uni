package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
public class ScreenTitlePersistenceAdapter implements ScreenTitleRepositoryPort {

    private static final String TARGET_UNIQUE_CONSTRAINT = "uq_screen_titles_slug";
    private static final java.util.regex.Pattern CONSTRAINT_PATTERN = java.util.regex.Pattern.compile(
            "(^|[^a-zA-Z0-9_])" + java.util.regex.Pattern.quote(TARGET_UNIQUE_CONSTRAINT) + "([^a-zA-Z0-9_]|$)",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private final SpringDataScreenTitleJpaRepository repository;

    public ScreenTitlePersistenceAdapter(SpringDataScreenTitleJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataScreenTitleJpaRepository cannot be null.");
    }

    @Override
    @Transactional
    public ScreenTitle save(ScreenTitle title) {
        Objects.requireNonNull(title, "ScreenTitle cannot be null.");

        String id = title.getId().toString();
        ScreenTitleJpaEntity entity = repository.findById(id)
                .map(existing -> {
                    ScreenTitlePersistenceMapper.updateEntity(existing, title);
                    return existing;
                })
                .orElseGet(() -> ScreenTitlePersistenceMapper.toEntity(title));

        try {
            ScreenTitleJpaEntity saved = repository.saveAndFlush(entity);
            return ScreenTitlePersistenceMapper.toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isSlugConstraintViolation(ex)) {
                throw new ScreenTitleSlugAlreadyExistsException(title.getSlug(), ex);
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
    public Optional<ScreenTitle> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(ScreenTitlePersistenceMapper::toDomain);
    }

    @Override
    @Transactional
    public Optional<ScreenTitle> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findByIdForUpdate(id.toString()).map(ScreenTitlePersistenceMapper::toDomain);
    }

    @Override
    public Optional<ScreenTitle> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return repository.findBySlug(slug.trim().toLowerCase(Locale.ROOT))
                .map(ScreenTitlePersistenceMapper::toDomain);
    }

    @Override
    public Optional<ScreenTitle> findPublishedById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findByIdAndStatus(id.toString(), "PUBLISHED")
                .map(ScreenTitlePersistenceMapper::toDomain);
    }

    @Override
    public Optional<ScreenTitle> findPublishedBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return repository.findBySlugAndStatus(slug.trim().toLowerCase(Locale.ROOT), "PUBLISHED")
                .map(ScreenTitlePersistenceMapper::toDomain);
    }

    @Override
    public List<ScreenTitle> findByFranchiseId(UUID franchiseId) {
        if (franchiseId == null) {
            return Collections.emptyList();
        }
        return repository.findByFranchiseId(franchiseId.toString())
                .stream()
                .map(ScreenTitlePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<ScreenTitle> findPublishedByFranchiseId(UUID franchiseId) {
        if (franchiseId == null) {
            return Collections.emptyList();
        }
        return repository.findPublishedByFranchiseId(franchiseId.toString())
                .stream()
                .map(ScreenTitlePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<ScreenTitle> findAll() {
        return repository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.ASC, "title", "id"))
                .stream()
                .map(ScreenTitlePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public ScreenTitlePage findPublishedTitles(int page, int size) {
        int effectivePage = Math.max(page, 0);
        int effectiveSize = Math.max(size, 1);
        Page<ScreenTitleJpaEntity> entityPage = repository.findPublishedTitles(
                PageRequest.of(effectivePage, effectiveSize)
        );
        List<ScreenTitle> items = entityPage.getContent().stream()
                .map(ScreenTitlePersistenceMapper::toDomain)
                .toList();
        return new ScreenTitlePage(
                items,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages()
        );
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
