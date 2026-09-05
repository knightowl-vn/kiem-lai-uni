package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.exceptions.ChapterNarrationAudioAlreadyExistsException;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure persistence adapter implementing {@link ChapterNarrationAudioRepositoryPort}.
 */
@Component
public class ChapterNarrationAudioPersistenceAdapter implements ChapterNarrationAudioRepositoryPort {

    private final SpringDataChapterNarrationAudioJpaRepository repository;

    public ChapterNarrationAudioPersistenceAdapter(SpringDataChapterNarrationAudioJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<ChapterNarrationAudio> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<ChapterNarrationAudio> findBySegmentIdAndManagedVoiceId(UUID segmentId, UUID managedVoiceId) {
        if (segmentId == null || managedVoiceId == null) {
            return Optional.empty();
        }
        return repository.findBySegmentIdAndManagedVoiceId(segmentId.toString(), managedVoiceId.toString())
                .map(this::toDomain);
    }

    @Override
    public List<ChapterNarrationAudio> findBySegmentId(UUID segmentId) {
        if (segmentId == null) {
            return List.of();
        }
        return repository.findBySegmentId(segmentId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private static final String UQ_SEGMENT_VOICE = "uq_novel_chapter_narration_audio_segment_voice";

    @Override
    public ChapterNarrationAudio save(ChapterNarrationAudio audio) {
        if (audio == null) {
            throw new IllegalArgumentException("ChapterNarrationAudio must not be null.");
        }
        ChapterNarrationAudioJpaEntity entity = toEntity(audio);
        try {
            ChapterNarrationAudioJpaEntity saved = repository.saveAndFlush(entity);
            return toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isConstraintViolation(ex, UQ_SEGMENT_VOICE)) {
                throw new ChapterNarrationAudioAlreadyExistsException(
                        audio.getSegmentId(),
                        audio.getManagedVoiceId()
                );
            }
            throw ex;
        }
    }

    @Override
    public List<ChapterNarrationAudio> saveAll(List<ChapterNarrationAudio> audios) {
        if (audios == null || audios.isEmpty()) {
            return List.of();
        }
        List<ChapterNarrationAudioJpaEntity> entities = audios.stream()
                .map(this::toEntity)
                .toList();
        try {
            List<ChapterNarrationAudioJpaEntity> saved = repository.saveAllAndFlush(entities);
            return saved.stream().map(this::toDomain).toList();
        } catch (DataIntegrityViolationException ex) {
            if (isConstraintViolation(ex, UQ_SEGMENT_VOICE)) {
                throw new ChapterNarrationAudioAlreadyExistsException(
                        "Duplicate chapter narration audio assignment for segment and voice."
                );
            }
            throw ex;
        }
    }

    private boolean isConstraintViolation(DataIntegrityViolationException ex, String targetConstraint) {
        String target = targetConstraint.toLowerCase();
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                if (cve.getConstraintName() != null
                        && cve.getConstraintName().toLowerCase().contains(target)) {
                    return true;
                }
            }
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(target)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ChapterNarrationAudioJpaEntity toEntity(ChapterNarrationAudio domain) {
        return new ChapterNarrationAudioJpaEntity(
                domain.getId().toString(),
                domain.getSegmentId().toString(),
                domain.getManagedVoiceId().toString(),
                domain.getMediaAssetId().toString(),
                domain.getGeneratedSynthesisRevision(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    private ChapterNarrationAudio toDomain(ChapterNarrationAudioJpaEntity entity) {
        return ChapterNarrationAudio.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getSegmentId()),
                UUID.fromString(entity.getManagedVoiceId()),
                UUID.fromString(entity.getMediaAssetId()),
                entity.getGeneratedSynthesisRevision(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
