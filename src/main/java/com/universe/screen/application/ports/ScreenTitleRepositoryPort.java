package com.universe.screen.application.ports;

import com.universe.screen.domain.ScreenTitle;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository port for {@link ScreenTitle}.
 */
public interface ScreenTitleRepositoryPort {

    ScreenTitle save(ScreenTitle title);

    Optional<ScreenTitle> findById(UUID id);

    Optional<ScreenTitle> findByIdForUpdate(UUID id);

    Optional<ScreenTitle> findBySlug(String slug);

    Optional<ScreenTitle> findPublishedById(UUID id);

    Optional<ScreenTitle> findPublishedBySlug(String slug);

    List<ScreenTitle> findByFranchiseId(UUID franchiseId);

    List<ScreenTitle> findPublishedByFranchiseId(UUID franchiseId);

    List<ScreenTitle> findAll();

    Map<UUID, ScreenTitle> findTitlesByIds(Set<UUID> titleIds);

    record ScreenTitlePage(
            List<ScreenTitle> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        public ScreenTitlePage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    ScreenTitlePage findPublishedTitles(int page, int size);

    boolean existsById(UUID id);

    boolean existsBySlug(String slug);
}
