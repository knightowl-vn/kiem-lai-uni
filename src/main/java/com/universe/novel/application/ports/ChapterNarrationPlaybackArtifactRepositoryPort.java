package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for immutable chapter-level narration playback artifacts.
 */
public interface ChapterNarrationPlaybackArtifactRepositoryPort {

    Optional<ChapterNarrationPlaybackArtifact> findById(UUID id);

    List<ChapterNarrationPlaybackArtifact> findByPlaybackId(UUID playbackId);

    ChapterNarrationPlaybackArtifact insert(ChapterNarrationPlaybackArtifact artifact);
}
