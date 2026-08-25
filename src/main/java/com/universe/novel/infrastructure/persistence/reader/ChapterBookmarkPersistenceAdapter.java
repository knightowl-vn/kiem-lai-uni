package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.exceptions.DuplicateChapterBookmarkException;
import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import com.universe.novel.domain.reader.UserChapterBookmark;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class ChapterBookmarkPersistenceAdapter implements ChapterBookmarkRepositoryPort {

    private final SpringDataChapterBookmarkJpaRepository repository;

    public ChapterBookmarkPersistenceAdapter(
            SpringDataChapterBookmarkJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataChapterBookmarkJpaRepository không được để trống."
        );
    }

    @Override
    public boolean existsByUserIdAndChapterId(UUID userId, UUID chapterId) {
        if (userId == null || chapterId == null) {
            return false;
        }
        return repository.existsByUserIdAndChapterId(userId.toString(), chapterId.toString());
    }

    @Override
    public void save(UserChapterBookmark bookmark) {
        if (bookmark == null) {
            throw new IllegalArgumentException("UserChapterBookmark không được để trống.");
        }

        ChapterBookmarkJpaEntity entity = new ChapterBookmarkJpaEntity(
                bookmark.getId().toString(),
                bookmark.getUserId().toString(),
                bookmark.getChapterId().toString(),
                bookmark.getCreatedAt()
        );

        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateBookmarkConstraintViolation(ex)) {
                throw new DuplicateChapterBookmarkException(bookmark.getUserId(), bookmark.getChapterId(), ex);
            }
            throw ex;
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteByUserIdAndChapterId(UUID userId, UUID chapterId) {
        if (userId == null || chapterId == null) {
            return 0;
        }
        return repository.deleteByUserIdAndChapterId(userId.toString(), chapterId.toString());
    }

    private boolean isDuplicateBookmarkConstraintViolation(DataIntegrityViolationException ex) {
        String targetConstraint = "uq_novel_chapter_bookmarks_user_chapter";
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
}
