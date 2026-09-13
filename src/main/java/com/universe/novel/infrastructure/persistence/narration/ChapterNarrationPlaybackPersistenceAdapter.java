package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationPlaybackRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationPlayback;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ChapterNarrationPlaybackPersistenceAdapter implements ChapterNarrationPlaybackRepositoryPort {

    private final SpringDataChapterNarrationPlaybackJpaRepository repository;

    public ChapterNarrationPlaybackPersistenceAdapter(SpringDataChapterNarrationPlaybackJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<ChapterNarrationPlayback> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<ChapterNarrationPlayback> findByChapterIdAndManagedVoiceId(UUID chapterId, UUID managedVoiceId) {
        if (chapterId == null || managedVoiceId == null) {
            return Optional.empty();
        }
        return repository.findByChapterIdAndManagedVoiceId(chapterId.toString(), managedVoiceId.toString())
                .map(this::toDomain);
    }

    @Override
    public ChapterNarrationPlayback save(ChapterNarrationPlayback playback) {
        if (playback == null) {
            throw new IllegalArgumentException("ChapterNarrationPlayback must not be null.");
        }
        ChapterNarrationPlaybackJpaEntity saved = repository.saveAndFlush(toEntity(playback));
        return toDomain(saved);
    }

    private ChapterNarrationPlaybackJpaEntity toEntity(ChapterNarrationPlayback playback) {
        return new ChapterNarrationPlaybackJpaEntity(
                playback.getId().toString(),
                playback.getChapterId().toString(),
                playback.getManagedVoiceId().toString(),
                playback.getCurrentArtifactId() != null ? playback.getCurrentArtifactId().toString() : null,
                playback.getVersion(),
                playback.getCreatedAt(),
                playback.getUpdatedAt()
        );
    }

    private ChapterNarrationPlayback toDomain(ChapterNarrationPlaybackJpaEntity entity) {
        return ChapterNarrationPlayback.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getChapterId()),
                UUID.fromString(entity.getManagedVoiceId()),
                entity.getCurrentArtifactId() != null ? UUID.fromString(entity.getCurrentArtifactId()) : null,
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
