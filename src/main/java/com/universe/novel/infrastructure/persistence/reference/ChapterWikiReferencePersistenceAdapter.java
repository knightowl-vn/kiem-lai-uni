package com.universe.novel.infrastructure.persistence.reference;

import com.universe.novel.application.ports.ChapterWikiReferenceRepositoryPort;
import com.universe.novel.domain.reference.ChapterWikiReference;
import com.universe.novel.domain.reference.ChapterWikiReferenceScope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ChapterWikiReferencePersistenceAdapter implements ChapterWikiReferenceRepositoryPort {

    private final SpringDataChapterWikiReferenceJpaRepository repository;

    public ChapterWikiReferencePersistenceAdapter(SpringDataChapterWikiReferenceJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "Repository không được để trống.");
    }

    @Override
    public Optional<ChapterWikiReference> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<ChapterWikiReference> findByChapterIdAndNormalizedTermAndOccurrenceIndex(
            UUID chapterId,
            String normalizedTerm,
            int occurrenceIndex
    ) {
        if (chapterId == null || normalizedTerm == null || normalizedTerm.isBlank()) {
            return Optional.empty();
        }
        return repository.findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                chapterId.toString(),
                normalizedTerm,
                occurrenceIndex
        ).map(this::toDomain);
    }

    @Override
    public List<ChapterWikiReference> findByChapterId(UUID chapterId) {
        if (chapterId == null) {
            return List.of();
        }
        return repository.findByChapterIdOrderByNormalizedTermAscOccurrenceIndexAsc(chapterId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<ChapterWikiReference> findByChapterIdAndNormalizedTerm(UUID chapterId, String normalizedTerm) {
        if (chapterId == null || normalizedTerm == null || normalizedTerm.isBlank()) {
            return List.of();
        }
        return repository.findByChapterIdAndNormalizedTerm(chapterId.toString(), normalizedTerm)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean existsByChapterIdAndNormalizedTermAndOccurrenceIndex(
            UUID chapterId,
            String normalizedTerm,
            int occurrenceIndex
    ) {
        if (chapterId == null || normalizedTerm == null || normalizedTerm.isBlank()) {
            return false;
        }
        return repository.existsByChapterIdAndNormalizedTermAndOccurrenceIndex(
                chapterId.toString(),
                normalizedTerm,
                occurrenceIndex
        );
    }

    @Override
    public ChapterWikiReference save(ChapterWikiReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("ChapterWikiReference không được để trống.");
        }

        ChapterWikiReferenceJpaEntity entity = toEntity(reference);
        ChapterWikiReferenceJpaEntity saved = repository.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    public void delete(ChapterWikiReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("ChapterWikiReference không được để trống.");
        }
        repository.deleteById(reference.getId().toString());
        repository.flush();
    }

    @Override
    public void deleteAllByChapterId(UUID chapterId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("Chapter ID không được để trống.");
        }
        repository.deleteAllByChapterId(chapterId.toString());
    }

    private ChapterWikiReferenceJpaEntity toEntity(ChapterWikiReference domain) {
        ChapterWikiReferenceJpaEntity entity = new ChapterWikiReferenceJpaEntity();
        entity.setId(domain.getId().toString());
        entity.setChapterId(domain.getChapterId().toString());
        entity.setTerm(domain.getTerm());
        entity.setNormalizedTerm(domain.getNormalizedTerm());
        entity.setReferenceScope(domain.getReferenceScope().name());
        entity.setOccurrenceIndex(domain.getOccurrenceIndex());
        entity.setContextSnippet(domain.getContextSnippet());
        entity.setBoundContentVersion(domain.getBoundContentVersion());
        entity.setWikiArticleId(domain.getWikiArticleId().toString());
        entity.setCreatedBy(domain.getCreatedBy().toString());
        entity.setUpdatedBy(domain.getUpdatedBy().toString());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private ChapterWikiReference toDomain(ChapterWikiReferenceJpaEntity entity) {
        return ChapterWikiReference.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getChapterId()),
                entity.getTerm(),
                entity.getNormalizedTerm(),
                ChapterWikiReferenceScope.valueOf(entity.getReferenceScope()),
                entity.getOccurrenceIndex(),
                entity.getContextSnippet(),
                entity.getBoundContentVersion(),
                UUID.fromString(entity.getWikiArticleId()),
                UUID.fromString(entity.getCreatedBy()),
                UUID.fromString(entity.getUpdatedBy()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
