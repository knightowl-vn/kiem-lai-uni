package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataChapterNarrationPlaybackJpaRepository
        extends JpaRepository<ChapterNarrationPlaybackJpaEntity, String> {

    Optional<ChapterNarrationPlaybackJpaEntity> findByChapterIdAndManagedVoiceId(
            String chapterId,
            String managedVoiceId
    );
}
