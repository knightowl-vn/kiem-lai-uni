package com.universe.screen.application.ports;

import com.universe.screen.domain.ScreenFranchise;

import java.util.Optional;
import java.util.UUID;

/**
 * Minimal identity-oriented repository port for {@link ScreenFranchise}.
 */
public interface ScreenFranchiseRepositoryPort {

    ScreenFranchise save(ScreenFranchise franchise);

    Optional<ScreenFranchise> findById(UUID id);

    Optional<ScreenFranchise> findBySlug(String slug);

    boolean existsById(UUID id);

    boolean existsBySlug(String slug);
}
