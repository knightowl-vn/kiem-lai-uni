package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure persistence adapter implementing {@link ChapterNarrationAudioFailureRepositoryPort}.
 */
@Component
public class ChapterNarrationAudioFailurePersistenceAdapter implements ChapterNarrationAudioFailureRepositoryPort {

    private final SpringDataChapterNarrationAudioFailureJpaRepository repository;

    public ChapterNarrationAudioFailurePersistenceAdapter(SpringDataChapterNarrationAudioFailureJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<ChapterNarrationAudioFailure> findBySegmentIdAndManagedVoiceId(UUID segmentId, UUID managedVoiceId) {
        if (segmentId == null || managedVoiceId == null) {
            return Optional.empty();
        }
        return repository.findBySegmentIdAndManagedVoiceId(segmentId.toString(), managedVoiceId.toString())
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public ChapterNarrationAudioFailure save(ChapterNarrationAudioFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("ChapterNarrationAudioFailure must not be null.");
        }
        ChapterNarrationAudioFailureJpaEntity entity = toEntity(failure);
        ChapterNarrationAudioFailureJpaEntity saved = repository.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional
    public void deleteBySegmentIdAndManagedVoiceId(UUID segmentId, UUID managedVoiceId) {
        if (segmentId == null || managedVoiceId == null) {
            return;
        }
        repository.deleteBySegmentIdAndManagedVoiceId(segmentId.toString(), managedVoiceId.toString());
    }

    private ChapterNarrationAudioFailureJpaEntity toEntity(ChapterNarrationAudioFailure domain) {
        return new ChapterNarrationAudioFailureJpaEntity(
                domain.getId().toString(),
                domain.getSegmentId().toString(),
                domain.getManagedVoiceId().toString(),
                domain.getOperation(),
                domain.getStage(),
                domain.getAttemptedSynthesisRevision(),
                domain.getFailureCount(),
                domain.getErrorType(),
                domain.getErrorMessage(),
                domain.getFirstFailedAt(),
                domain.getLastFailedAt()
        );
    }

    private ChapterNarrationAudioFailure toDomain(ChapterNarrationAudioFailureJpaEntity entity) {
        return ChapterNarrationAudioFailure.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getSegmentId()),
                UUID.fromString(entity.getManagedVoiceId()),
                entity.getOperation(),
                entity.getStage(),
                entity.getAttemptedSynthesisRevision(),
                entity.getFailureCount(),
                entity.getErrorType(),
                entity.getErrorMessage(),
                entity.getFirstFailedAt(),
                entity.getLastFailedAt()
        );
    }
}
