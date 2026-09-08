package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure persistence adapter implementing {@link ChapterNarrationSegmentRepositoryPort}.
 */
@Component
public class ChapterNarrationSegmentPersistenceAdapter implements ChapterNarrationSegmentRepositoryPort {

    private final SpringDataChapterNarrationSegmentJpaRepository repository;

    public ChapterNarrationSegmentPersistenceAdapter(SpringDataChapterNarrationSegmentJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<ChapterNarrationSegment> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<ChapterNarrationSegment> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findByIdForUpdate(id.toString()).map(this::toDomain);
    }

    @Override
    public List<ChapterNarrationSegment> findByChapterId(UUID chapterId) {
        if (chapterId == null) {
            return List.of();
        }
        return repository.findByChapterIdOrderBySegmentIndexAsc(chapterId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<ChapterNarrationSegment> findByChapterIdAndStatus(UUID chapterId, ChapterNarrationSegmentStatus status) {
        if (chapterId == null || status == null) {
            return List.of();
        }
        return repository.findByChapterIdAndStatusOrderBySegmentIndexAsc(chapterId.toString(), status.name())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public ChapterNarrationSegment save(ChapterNarrationSegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("Segment must not be null.");
        }
        ChapterNarrationSegmentJpaEntity entity = toEntity(segment);
        ChapterNarrationSegmentJpaEntity saved = repository.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    public List<ChapterNarrationSegment> saveAll(List<ChapterNarrationSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        List<ChapterNarrationSegmentJpaEntity> entities = segments.stream()
                .map(this::toEntity)
                .toList();
        List<ChapterNarrationSegmentJpaEntity> saved = repository.saveAllAndFlush(entities);
        return saved.stream().map(this::toDomain).toList();
    }

    private ChapterNarrationSegmentJpaEntity toEntity(ChapterNarrationSegment domain) {
        return new ChapterNarrationSegmentJpaEntity(
                domain.getId().toString(),
                domain.getChapterId().toString(),
                domain.getSegmentIndex(),
                domain.getText(),
                domain.getCharacterCount(),
                domain.getContentHash(),
                domain.getStatus().name(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    private ChapterNarrationSegment toDomain(ChapterNarrationSegmentJpaEntity entity) {
        return ChapterNarrationSegment.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getChapterId()),
                entity.getSegmentIndex(),
                entity.getText(),
                entity.getCharacterCount(),
                entity.getContentHash(),
                ChapterNarrationSegmentStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
