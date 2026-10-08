package com.universe.screen.application.ports;

import com.universe.screen.domain.ScreenFranchiseMembership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal identity-oriented repository port for {@link ScreenFranchiseMembership}.
 */
public interface ScreenFranchiseMembershipRepositoryPort {

    /**
     * Persists or updates a {@link ScreenFranchiseMembership}.
     *
     * @param membership membership aggregate to persist
     * @return persisted membership
     * @throws com.universe.screen.application.exceptions.DuplicateScreenFranchiseMembershipException if duplicate membership occurs
     */
    ScreenFranchiseMembership save(ScreenFranchiseMembership membership);

    Optional<ScreenFranchiseMembership> findById(UUID id);

    Optional<ScreenFranchiseMembership> findByFranchiseIdAndTitleId(UUID franchiseId, UUID titleId);

    List<ScreenFranchiseMembership> findByFranchiseId(UUID franchiseId);

    boolean existsById(UUID id);

    boolean existsByFranchiseIdAndTitleId(UUID franchiseId, UUID titleId);

    void delete(ScreenFranchiseMembership membership);
}
