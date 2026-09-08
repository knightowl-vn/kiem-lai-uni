package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationPlaybackArtifactRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ChapterNarrationPlaybackArtifactPersistenceAdapter
        implements ChapterNarrationPlaybackArtifactRepositoryPort {

    private final SpringDataChapterNarrationPlaybackArtifactJpaRepository repository;
    private final EntityManager entityManager;

    public ChapterNarrationPlaybackArtifactPersistenceAdapter(
            SpringDataChapterNarrationPlaybackArtifactJpaRepository repository,
            EntityManager entityManager
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    public Optional<ChapterNarrationPlaybackArtifact> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public List<ChapterNarrationPlaybackArtifact> findByPlaybackId(UUID playbackId) {
        if (playbackId == null) {
            return List.of();
        }
        return repository.findByPlaybackIdOrderByCreatedAtDesc(playbackId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public ChapterNarrationPlaybackArtifact insert(ChapterNarrationPlaybackArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("ChapterNarrationPlaybackArtifact must not be null.");
        }
        ChapterNarrationPlaybackArtifactJpaEntity entity = toEntity(artifact);
        entityManager.persist(entity);
        entityManager.flush();
        return toDomain(entity);
    }

    private ChapterNarrationPlaybackArtifactJpaEntity toEntity(ChapterNarrationPlaybackArtifact artifact) {
        return new ChapterNarrationPlaybackArtifactJpaEntity(
                artifact.getId().toString(),
                artifact.getPlaybackId().toString(),
                artifact.getChapterId().toString(),
                artifact.getManagedVoiceId().toString(),
                artifact.getSourceContentVersion(),
                artifact.getSynthesisRevision(),
                artifact.getManifestHash(),
                artifact.getMediaAssetId().toString(),
                artifact.getDurationMillis(),
                artifact.getCueCount(),
                artifact.getCodecMimeType(),
                artifact.getCreatedAt()
        );
    }

    private ChapterNarrationPlaybackArtifact toDomain(ChapterNarrationPlaybackArtifactJpaEntity entity) {
        return ChapterNarrationPlaybackArtifact.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getPlaybackId()),
                UUID.fromString(entity.getChapterId()),
                UUID.fromString(entity.getManagedVoiceId()),
                entity.getSourceContentVersion(),
                entity.getSynthesisRevision(),
                entity.getManifestHash(),
                UUID.fromString(entity.getMediaAssetId()),
                entity.getDurationMillis(),
                entity.getCueCount(),
                entity.getCodecMimeType(),
                entity.getCreatedAt()
        );
    }
}
