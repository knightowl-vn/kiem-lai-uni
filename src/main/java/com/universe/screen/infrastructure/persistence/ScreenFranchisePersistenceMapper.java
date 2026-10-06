package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenFranchise;

import java.util.Objects;
import java.util.UUID;

/**
 * Two-way persistence mapper for {@link ScreenFranchise}.
 */
public final class ScreenFranchisePersistenceMapper {

    private ScreenFranchisePersistenceMapper() {
    }

    public static ScreenFranchiseJpaEntity toEntity(ScreenFranchise domain) {
        Objects.requireNonNull(domain, "ScreenFranchise domain object cannot be null.");
        return new ScreenFranchiseJpaEntity(
                domain.getId().toString(),
                domain.getSlug(),
                domain.getName()
        );
    }

    public static void updateEntity(ScreenFranchiseJpaEntity entity, ScreenFranchise domain) {
        Objects.requireNonNull(entity, "ScreenFranchiseJpaEntity cannot be null.");
        Objects.requireNonNull(domain, "ScreenFranchise domain object cannot be null.");
        entity.setSlug(domain.getSlug());
        entity.setName(domain.getName());
    }

    public static ScreenFranchise toDomain(ScreenFranchiseJpaEntity entity) {
        Objects.requireNonNull(entity, "ScreenFranchiseJpaEntity cannot be null.");
        return new ScreenFranchise(
                UUID.fromString(entity.getId()),
                entity.getSlug(),
                entity.getName()
        );
    }
}
