package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenFranchiseMembership;

import java.util.Objects;
import java.util.UUID;

/**
 * Two-way persistence mapper for {@link ScreenFranchiseMembership}.
 */
public final class ScreenFranchiseMembershipPersistenceMapper {

    private ScreenFranchiseMembershipPersistenceMapper() {
    }

    public static ScreenFranchiseMembershipJpaEntity toEntity(ScreenFranchiseMembership domain) {
        Objects.requireNonNull(domain, "ScreenFranchiseMembership domain object cannot be null.");
        return new ScreenFranchiseMembershipJpaEntity(
                domain.getId().toString(),
                domain.getFranchiseId().toString(),
                domain.getTitleId().toString(),
                domain.getDisplayOrder()
        );
    }

    public static void updateEntity(ScreenFranchiseMembershipJpaEntity entity, ScreenFranchiseMembership domain) {
        Objects.requireNonNull(entity, "ScreenFranchiseMembershipJpaEntity cannot be null.");
        Objects.requireNonNull(domain, "ScreenFranchiseMembership domain object cannot be null.");
        entity.setFranchiseId(domain.getFranchiseId().toString());
        entity.setTitleId(domain.getTitleId().toString());
        entity.setDisplayOrder(domain.getDisplayOrder());
    }

    public static ScreenFranchiseMembership toDomain(ScreenFranchiseMembershipJpaEntity entity) {
        Objects.requireNonNull(entity, "ScreenFranchiseMembershipJpaEntity cannot be null.");
        return new ScreenFranchiseMembership(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getFranchiseId()),
                UUID.fromString(entity.getTitleId()),
                entity.getDisplayOrder()
        );
    }
}
