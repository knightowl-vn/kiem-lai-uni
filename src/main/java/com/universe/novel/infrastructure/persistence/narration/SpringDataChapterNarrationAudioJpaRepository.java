package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataChapterNarrationAudioJpaRepository
        extends JpaRepository<ChapterNarrationAudioJpaEntity, String> {

    Optional<ChapterNarrationAudioJpaEntity> findBySegmentIdAndManagedVoiceId(String segmentId, String managedVoiceId);

    List<ChapterNarrationAudioJpaEntity> findBySegmentIdInAndManagedVoiceId(Collection<String> segmentIds, String managedVoiceId);

    List<ChapterNarrationAudioJpaEntity> findBySegmentId(String segmentId);
}
