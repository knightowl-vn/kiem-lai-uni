package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;

import java.util.List;
import java.util.UUID;

/**
 * Repository port for immutable chapter playback cue timelines.
 */
public interface ChapterNarrationPlaybackCueRepositoryPort {

    List<ChapterNarrationPlaybackCue> findByArtifactId(UUID artifactId);

    List<ChapterNarrationPlaybackCue> insertAll(List<ChapterNarrationPlaybackCue> cues);
}
