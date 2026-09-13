package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ChapterNarrationPlayback;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for stable chapter-level narration playback identities.
 */
public interface ChapterNarrationPlaybackRepositoryPort {

    Optional<ChapterNarrationPlayback> findById(UUID id);

    Optional<ChapterNarrationPlayback> findByChapterIdAndManagedVoiceId(UUID chapterId, UUID managedVoiceId);

    ChapterNarrationPlayback save(ChapterNarrationPlayback playback);
}
