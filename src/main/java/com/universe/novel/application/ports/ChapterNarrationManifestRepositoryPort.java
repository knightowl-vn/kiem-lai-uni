package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ChapterNarrationManifest;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound repository port for persisting and retrieving {@link ChapterNarrationManifest} aggregates.
 */
public interface ChapterNarrationManifestRepositoryPort {

    /**
     * Finds the chapter narration manifest by chapter ID.
     *
     * @param chapterId the chapter UUID
     * @return the manifest if present
     */
    Optional<ChapterNarrationManifest> findByChapterId(UUID chapterId);

    /**
     * Saves or updates a chapter narration manifest.
     *
     * @param manifest the aggregate to save
     * @return the saved aggregate
     */
    ChapterNarrationManifest save(ChapterNarrationManifest manifest);
}
