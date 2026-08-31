package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.exceptions.ReadingProgressConcurrencyException;
import com.universe.novel.application.ports.ReadingProgressRepositoryPort;
import com.universe.novel.domain.reader.UserReadingProgress;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReadingProgressPersistenceAdapter implements ReadingProgressRepositoryPort {

    private final SpringDataReadingProgressJpaRepository repository;

    public ReadingProgressPersistenceAdapter(
            SpringDataReadingProgressJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataReadingProgressJpaRepository không được để trống."
        );
    }

    @Override
    public Optional<UserReadingProgress> findByUserId(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }

        return repository.findByUserId(userId.toString())
                .map(this::toDomain);
    }

    @Override
    public Optional<UserReadingProgress> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }

        return repository.findById(id.toString())
                .map(this::toDomain);
    }

    @Override
    public UserReadingProgress save(UserReadingProgress progress) {
        if (progress == null) {
            throw new IllegalArgumentException("UserReadingProgress không được để trống.");
        }

        String progressId = progress.getId().toString();
        Optional<ReadingProgressJpaEntity> existingEntity = repository.findById(progressId);

        ReadingProgressJpaEntity entity;

        if (existingEntity.isPresent()) {
            entity = existingEntity.get();
            entity.setLastOpenedChapterId(progress.getLastOpenedChapterId().toString());
            entity.setHighestReachedChapterNumber(progress.getHighestReachedChapterNumber());
            entity.setUpdatedAt(progress.getUpdatedAt());
        } else {
            entity = new ReadingProgressJpaEntity(
                    progressId,
                    progress.getUserId().toString(),
                    progress.getLastOpenedChapterId().toString(),
                    progress.getHighestReachedChapterNumber(),
                    progress.getCreatedAt(),
                    progress.getUpdatedAt()
            );
        }

        try {
            ReadingProgressJpaEntity savedEntity = repository.saveAndFlush(entity);
            return toDomain(savedEntity);
        } catch (OptimisticLockingFailureException ex) {
            throw new ReadingProgressConcurrencyException(progress.getUserId(), ex);
        } catch (DataIntegrityViolationException ex) {
            if (isUserUniqueConstraintViolation(ex)) {
                throw new ReadingProgressConcurrencyException(progress.getUserId(), ex);
            }
            throw ex;
        }
    }

    private boolean isUserUniqueConstraintViolation(DataIntegrityViolationException ex) {
        String targetConstraint = "uq_novel_reading_progress_user";
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null
                        && cve.getConstraintName().toLowerCase().contains(targetConstraint)) {
                    return true;
                }
            }
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(targetConstraint)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private UserReadingProgress toDomain(ReadingProgressJpaEntity entity) {
        return UserReadingProgress.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getUserId()),
                UUID.fromString(entity.getLastOpenedChapterId()),
                entity.getHighestReachedChapterNumber(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
