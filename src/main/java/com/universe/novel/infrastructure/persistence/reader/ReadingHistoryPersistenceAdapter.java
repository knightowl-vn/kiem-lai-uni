package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.exceptions.DuplicateReadingHistoryException;
import com.universe.novel.application.ports.ReadingHistoryRepositoryPort;
import com.universe.novel.domain.reader.UserChapterReadingHistory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReadingHistoryPersistenceAdapter implements ReadingHistoryRepositoryPort {

    private final SpringDataReadingHistoryJpaRepository repository;

    public ReadingHistoryPersistenceAdapter(
            SpringDataReadingHistoryJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataReadingHistoryJpaRepository không được để trống."
        );
    }

    @Override
    public Optional<UserChapterReadingHistory> findByUserIdAndChapterId(UUID userId, UUID chapterId) {
        if (userId == null || chapterId == null) {
            return Optional.empty();
        }

        return repository.findByUserIdAndChapterId(userId.toString(), chapterId.toString())
                .map(this::toDomain);
    }

    @Override
    public UserChapterReadingHistory save(UserChapterReadingHistory history) {
        if (history == null) {
            throw new IllegalArgumentException("UserChapterReadingHistory không được để trống.");
        }

        String historyId = history.getId().toString();
        Optional<ReadingHistoryJpaEntity> existingEntity = repository.findById(historyId);

        ReadingHistoryJpaEntity entity;
        if (existingEntity.isPresent()) {
            entity = existingEntity.get();
            entity.setLastReadAt(history.getLastReadAt());
        } else {
            entity = new ReadingHistoryJpaEntity(
                    historyId,
                    history.getUserId().toString(),
                    history.getChapterId().toString(),
                    history.getFirstReadAt(),
                    history.getLastReadAt()
            );
        }

        try {
            ReadingHistoryJpaEntity savedEntity = repository.saveAndFlush(entity);
            return toDomain(savedEntity);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateConstraintViolation(ex)) {
                throw new DuplicateReadingHistoryException(history.getUserId(), history.getChapterId(), ex);
            }
            throw ex;
        }
    }

    private boolean isDuplicateConstraintViolation(DataIntegrityViolationException ex) {
        String targetConstraint = "uq_novel_reading_history_user_chapter";
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

    private UserChapterReadingHistory toDomain(ReadingHistoryJpaEntity entity) {
        return UserChapterReadingHistory.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getUserId()),
                UUID.fromString(entity.getChapterId()),
                entity.getFirstReadAt(),
                entity.getLastReadAt()
        );
    }
}
