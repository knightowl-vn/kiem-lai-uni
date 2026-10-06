package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class ScreenTitlePersistenceAdapter implements ScreenTitleRepositoryPort {

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

        ScreenTitleJpaEntity saved = repository.saveAndFlush(entity);
        return ScreenTitlePersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<ScreenTitle> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(ScreenTitlePersistenceMapper::toDomain);
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
