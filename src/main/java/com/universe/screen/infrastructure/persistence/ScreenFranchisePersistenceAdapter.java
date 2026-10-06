package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class ScreenFranchisePersistenceAdapter implements ScreenFranchiseRepositoryPort {

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

        ScreenFranchiseJpaEntity saved = repository.saveAndFlush(entity);
        return ScreenFranchisePersistenceMapper.toDomain(saved);
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
