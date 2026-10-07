package com.universe.screen.application.ports;

import com.universe.screen.domain.ScreenTitle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for {@link ScreenTitle}.
 */
public interface ScreenTitleRepositoryPort {

    ScreenTitle save(ScreenTitle title);

    Optional<ScreenTitle> findById(UUID id);

    Optional<ScreenTitle> findBySlug(String slug);

    Optional<ScreenTitle> findPublishedById(UUID id);

    Optional<ScreenTitle> findPublishedBySlug(String slug);

    List<ScreenTitle> findByFranchiseId(UUID franchiseId);

    List<ScreenTitle> findPublishedByFranchiseId(UUID franchiseId);

    boolean existsById(UUID id);

    boolean existsBySlug(String slug);
}
