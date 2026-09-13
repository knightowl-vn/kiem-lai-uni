package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure persistence adapter implementing {@link ChapterNarrationManifestRepositoryPort}.
 */
@Component
public class ChapterNarrationManifestPersistenceAdapter implements ChapterNarrationManifestRepositoryPort {

    private final SpringDataChapterNarrationManifestJpaRepository repository;

    public ChapterNarrationManifestPersistenceAdapter(SpringDataChapterNarrationManifestJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<ChapterNarrationManifest> findByChapterId(UUID chapterId) {
        if (chapterId == null) {
            return Optional.empty();
        }
        return repository.findById(chapterId.toString()).map(this::toDomain);
    }

    @Override
    @Transactional
    public ChapterNarrationManifest save(ChapterNarrationManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("ChapterNarrationManifest must not be null.");
        }
        ChapterNarrationManifestJpaEntity entity = toEntity(manifest);
        ChapterNarrationManifestJpaEntity saved = repository.saveAndFlush(entity);
        return toDomain(saved);
    }

    private ChapterNarrationManifestJpaEntity toEntity(ChapterNarrationManifest domain) {
        return new ChapterNarrationManifestJpaEntity(
                domain.getChapterId().toString(),
                domain.getSourceContentVersion(),
                domain.getManifestHash(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    private ChapterNarrationManifest toDomain(ChapterNarrationManifestJpaEntity entity) {
        return ChapterNarrationManifest.rehydrate(
                UUID.fromString(entity.getChapterId()),
                entity.getSourceContentVersion(),
                entity.getManifestHash(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
