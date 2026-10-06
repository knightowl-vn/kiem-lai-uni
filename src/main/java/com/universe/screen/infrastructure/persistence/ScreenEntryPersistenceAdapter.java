package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
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

        ScreenEntryJpaEntity saved = repository.saveAndFlush(entity);
        return ScreenEntryPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<ScreenEntry> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(ScreenEntryPersistenceMapper::toDomain);
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
