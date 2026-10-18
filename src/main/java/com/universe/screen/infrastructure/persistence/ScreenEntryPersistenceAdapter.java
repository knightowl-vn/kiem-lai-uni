package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
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
public class ScreenEntryPersistenceAdapter implements ScreenEntryRepositoryPort {

    private static final String TARGET_UNIQUE_CONSTRAINT = "uq_screen_entries_title_slug";
    private static final java.util.regex.Pattern CONSTRAINT_PATTERN = java.util.regex.Pattern.compile(
            "(^|[^a-zA-Z0-9_])" + java.util.regex.Pattern.quote(TARGET_UNIQUE_CONSTRAINT) + "([^a-zA-Z0-9_]|$)",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private final SpringDataScreenEntryJpaRepository repository;

    public ScreenEntryPersistenceAdapter(SpringDataScreenEntryJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataScreenEntryJpaRepository cannot be null.");
    }

    @Override
    @Transactional
    public ScreenEntry save(ScreenEntry entry) {
        Objects.requireNonNull(entry, "ScreenEntry cannot be null.");

        String id = entry.getId().toString();
        ScreenEntryJpaEntity entity = repository.findById(id)
                .map(existing -> {
                    ScreenEntryPersistenceMapper.updateEntity(existing, entry);
                    return existing;
                })
                .orElseGet(() -> ScreenEntryPersistenceMapper.toEntity(entry));

        try {
            ScreenEntryJpaEntity saved = repository.saveAndFlush(entity);
            return ScreenEntryPersistenceMapper.toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isSlugConstraintViolation(ex)) {
                throw new ScreenEntrySlugAlreadyExistsException(entry.getSlug(), ex);
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
    public Optional<ScreenEntry> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(ScreenEntryPersistenceMapper::toDomain);
    }

    @Override
    @Transactional
    public Optional<ScreenEntry> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findByIdForUpdate(id.toString()).map(ScreenEntryPersistenceMapper::toDomain);
    }

    @Override
    public Optional<ScreenEntry> findPublishedById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findByIdAndStatus(id.toString(), "PUBLISHED")
                .map(ScreenEntryPersistenceMapper::toDomain);
    }

    @Override
    public Optional<ScreenEntry> findByTitleIdAndSlug(UUID titleId, String slug) {
        if (titleId == null || slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTitleIdAndSlug(titleId.toString(), slug.trim().toLowerCase(Locale.ROOT))
                .map(ScreenEntryPersistenceMapper::toDomain);
    }

    @Override
    public Optional<ScreenEntry> findPublishedByTitleIdAndSlug(UUID titleId, String slug) {
        if (titleId == null || slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTitleIdAndSlugAndStatus(
                titleId.toString(),
                slug.trim().toLowerCase(Locale.ROOT),
                "PUBLISHED"
        ).map(ScreenEntryPersistenceMapper::toDomain);
    }

    @Override
    public List<ScreenEntry> findByTitleId(UUID titleId) {
        if (titleId == null) {
            return Collections.emptyList();
        }
        return repository.findByTitleId(titleId.toString())
                .stream()
                .map(ScreenEntryPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<ScreenEntry> findPublishedByTitleId(UUID titleId) {
        if (titleId == null) {
            return Collections.emptyList();
        }
        return repository.findPublishedByTitleId(titleId.toString())
                .stream()
                .map(ScreenEntryPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<ScreenEntry> findPublishedEpisodesByTitleId(UUID titleId) {
        if (titleId == null) {
            return Collections.emptyList();
        }
        return repository.findPublishedEpisodesByTitleId(titleId.toString())
                .stream()
                .map(ScreenEntryPersistenceMapper::toDomain)
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
    public boolean existsByTitleIdAndSlug(UUID titleId, String slug) {
        if (titleId == null || slug == null || slug.isBlank()) {
            return false;
        }
        return repository.existsByTitleIdAndSlug(titleId.toString(), slug.trim().toLowerCase(Locale.ROOT));
    }
}
