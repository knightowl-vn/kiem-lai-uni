package com.universe.screen.application.ports;

import com.universe.screen.domain.ScreenFranchise;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal identity-oriented repository port for {@link ScreenFranchise}.
 */
public interface ScreenFranchiseRepositoryPort {

    /**
     * Persists or updates a {@link ScreenFranchise}.
     *
     * @param franchise franchise aggregate to persist
     * @return persisted franchise
     * @throws com.universe.screen.application.exceptions.ScreenFranchiseSlugAlreadyExistsException if unique slug conflict occurs
     */
    ScreenFranchise save(ScreenFranchise franchise);

    Optional<ScreenFranchise> findById(UUID id);

    Optional<ScreenFranchise> findBySlug(String slug);

    List<ScreenFranchise> findAll();

    boolean existsById(UUID id);

    boolean existsBySlug(String slug);
}
