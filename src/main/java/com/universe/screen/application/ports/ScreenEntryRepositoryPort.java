package com.universe.screen.application.ports;

import com.universe.screen.domain.ScreenEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal identity-oriented repository port for {@link ScreenEntry}.
 */
public interface ScreenEntryRepositoryPort {

    ScreenEntry save(ScreenEntry entry);

    Optional<ScreenEntry> findById(UUID id);

    Optional<ScreenEntry> findByTitleIdAndSlug(UUID titleId, String slug);

    List<ScreenEntry> findByTitleId(UUID titleId);

    boolean existsById(UUID id);

    boolean existsByTitleIdAndSlug(UUID titleId, String slug);
}
