package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataChapterNarrationPlaybackCueJpaRepository
        extends JpaRepository<ChapterNarrationPlaybackCueJpaEntity, ChapterNarrationPlaybackCueJpaId> {

    List<ChapterNarrationPlaybackCueJpaEntity> findByIdArtifactIdOrderByIdCueOrdinalAsc(String artifactId);
}
