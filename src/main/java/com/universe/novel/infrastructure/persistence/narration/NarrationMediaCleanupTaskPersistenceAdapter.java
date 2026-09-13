package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.exceptions.NarrationMediaCleanupTaskAlreadyExistsException;
import com.universe.novel.application.ports.NarrationMediaCleanupTaskRepositoryPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure persistence adapter implementing {@link NarrationMediaCleanupTaskRepositoryPort}.
 */
@Component
public class NarrationMediaCleanupTaskPersistenceAdapter implements NarrationMediaCleanupTaskRepositoryPort {

    private static final String UQ_MEDIA_ASSET = "uq_novel_narration_media_cleanup_tasks_media_asset";

    private final SpringDataNarrationMediaCleanupTaskJpaRepository repository;

    public NarrationMediaCleanupTaskPersistenceAdapter(SpringDataNarrationMediaCleanupTaskJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<NarrationMediaCleanupTask> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<NarrationMediaCleanupTask> findByMediaAssetId(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return Optional.empty();
        }
        return repository.findByMediaAssetId(mediaAssetId.toString()).map(this::toDomain);
    }

    @Override
    public List<NarrationMediaCleanupTask> findOldest(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return repository.findOldest(PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public NarrationMediaCleanupTask save(NarrationMediaCleanupTask task) {
        if (task == null) {
            throw new IllegalArgumentException("NarrationMediaCleanupTask must not be null.");
        }
        NarrationMediaCleanupTaskJpaEntity entity = toEntity(task);
        try {
            NarrationMediaCleanupTaskJpaEntity saved = repository.saveAndFlush(entity);
            return toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isConstraintViolation(ex, UQ_MEDIA_ASSET)) {
                throw new NarrationMediaCleanupTaskAlreadyExistsException(task.getMediaAssetId());
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

    @Override
    @Transactional
    public void deleteById(UUID id) {
        if (id == null) {
            return;
        }
        repository.deleteById(id.toString());
        repository.flush();
    }

    @Override
    @Transactional
    public void deleteByMediaAssetId(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return;
        }
        repository.deleteByMediaAssetId(mediaAssetId.toString());
        repository.flush();
    }

    private NarrationMediaCleanupTaskJpaEntity toEntity(NarrationMediaCleanupTask domain) {
        return new NarrationMediaCleanupTaskJpaEntity(
                domain.getId().toString(),
                domain.getMediaAssetId().toString(),
                domain.getReason(),
                domain.getAttemptCount(),
                domain.getLastErrorType(),
                domain.getCreatedAt(),
                domain.getLastAttemptAt(),
                domain.getUpdatedAt()
        );
    }

    private NarrationMediaCleanupTask toDomain(NarrationMediaCleanupTaskJpaEntity entity) {
        return NarrationMediaCleanupTask.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getMediaAssetId()),
                entity.getReason(),
                entity.getAttemptCount(),
                entity.getLastErrorType(),
                entity.getCreatedAt(),
                entity.getLastAttemptAt(),
                entity.getUpdatedAt()
        );
    }
}
