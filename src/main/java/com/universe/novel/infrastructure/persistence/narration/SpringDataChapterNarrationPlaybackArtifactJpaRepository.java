package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataChapterNarrationPlaybackArtifactJpaRepository
        extends JpaRepository<ChapterNarrationPlaybackArtifactJpaEntity, String> {

    List<ChapterNarrationPlaybackArtifactJpaEntity> findByPlaybackIdOrderByCreatedAtDesc(String playbackId);
}
