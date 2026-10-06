package com.universe.screen.application.ports;

import com.universe.screen.domain.ScreenTitle;

import java.util.Optional;
import java.util.UUID;

/**
 * Minimal identity-oriented repository port for {@link ScreenTitle}.
 */
public interface ScreenTitleRepositoryPort {

    ScreenTitle save(ScreenTitle title);

    Optional<ScreenTitle> findById(UUID id);

    Optional<ScreenTitle> findBySlug(String slug);

    boolean existsById(UUID id);

    boolean existsBySlug(String slug);
}
