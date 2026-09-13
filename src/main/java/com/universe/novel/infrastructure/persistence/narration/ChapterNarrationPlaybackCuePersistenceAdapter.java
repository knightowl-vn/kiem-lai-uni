package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class ChapterNarrationPlaybackCuePersistenceAdapter implements ChapterNarrationPlaybackCueRepositoryPort {

    private final SpringDataChapterNarrationPlaybackCueJpaRepository repository;
    private final EntityManager entityManager;

    public ChapterNarrationPlaybackCuePersistenceAdapter(
            SpringDataChapterNarrationPlaybackCueJpaRepository repository,
            EntityManager entityManager
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    public List<ChapterNarrationPlaybackCue> findByArtifactId(UUID artifactId) {
        if (artifactId == null) {
            return List.of();
        }
        return repository.findByIdArtifactIdOrderByIdCueOrdinalAsc(artifactId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public List<ChapterNarrationPlaybackCue> insertAll(List<ChapterNarrationPlaybackCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return List.of();
        }
        List<ChapterNarrationPlaybackCueJpaEntity> entities = cues.stream().map(this::toEntity).toList();
        entities.forEach(entityManager::persist);
        entityManager.flush();
        return entities.stream().map(this::toDomain).toList();
    }

    private ChapterNarrationPlaybackCueJpaEntity toEntity(ChapterNarrationPlaybackCue cue) {
        return new ChapterNarrationPlaybackCueJpaEntity(
                new ChapterNarrationPlaybackCueJpaId(
                        cue.getArtifactId().toString(),
                        cue.getCueOrdinal()
                ),
                cue.getSegmentId().toString(),
                cue.getSegmentIndex(),
                cue.getStartMillis(),
                cue.getEndMillis()
        );
    }

    private ChapterNarrationPlaybackCue toDomain(ChapterNarrationPlaybackCueJpaEntity entity) {
        return ChapterNarrationPlaybackCue.rehydrate(
                UUID.fromString(entity.getId().getArtifactId()),
                entity.getId().getCueOrdinal(),
                UUID.fromString(entity.getSegmentId()),
                entity.getSegmentIndex(),
                entity.getStartMillis(),
                entity.getEndMillis()
        );
    }
}
