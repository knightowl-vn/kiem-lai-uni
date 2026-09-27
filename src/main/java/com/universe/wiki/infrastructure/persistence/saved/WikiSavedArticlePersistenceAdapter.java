package com.universe.wiki.infrastructure.persistence.saved;

import com.universe.wiki.application.exceptions.DuplicateWikiSavedArticleException;
import com.universe.wiki.application.ports.WikiSavedArticleRepositoryPort;
import com.universe.wiki.domain.saved.UserSavedWikiArticle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Adapter persistence thực thi WikiSavedArticleRepositoryPort.
 *
 * Đảm bảo:
 * 1. Chuyển đổi giữa domain aggregate UserSavedWikiArticle và JPA Entity;
 * 2. Bắt đúng ràng buộc duy nhất uq_wiki_saved_articles_user_article để ném DuplicateWikiSavedArticleException;
 * 3. Không nuốt nhầm các lỗi integrity khác (như foreign key hay check constraint).
 */
@Component
public class WikiSavedArticlePersistenceAdapter implements WikiSavedArticleRepositoryPort {

    private final SpringDataWikiSavedArticleJpaRepository repository;

    public WikiSavedArticlePersistenceAdapter(
            SpringDataWikiSavedArticleJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataWikiSavedArticleJpaRepository không được để trống."
        );
    }

    @Override
    public boolean existsByUserIdAndArticleId(UUID userId, UUID articleId) {
        if (userId == null || articleId == null) {
            return false;
        }
        return repository.existsByUserIdAndArticleId(
                userId.toString(),
                articleId.toString()
        );
    }

    @Override
    @Transactional
    public void save(UserSavedWikiArticle savedArticle) {
        if (savedArticle == null) {
            throw new IllegalArgumentException("UserSavedWikiArticle không được để trống.");
        }

        WikiSavedArticleJpaEntity entity = new WikiSavedArticleJpaEntity(
                savedArticle.getId().toString(),
                savedArticle.getUserId().toString(),
                savedArticle.getArticleId().toString(),
                savedArticle.getCreatedAt()
        );

        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateConstraintViolation(ex)) {
                throw new DuplicateWikiSavedArticleException(
                        savedArticle.getUserId(),
                        savedArticle.getArticleId(),
                        ex
                );
            }
            throw ex;
        }
    }

    @Override
    @Transactional
    public boolean deleteByUserIdAndArticleId(UUID userId, UUID articleId) {
        if (userId == null || articleId == null) {
            return false;
        }
        return repository.deleteByUserIdAndArticleId(
                userId.toString(),
                articleId.toString()
        ) > 0;
    }

    private boolean isDuplicateConstraintViolation(DataIntegrityViolationException ex) {
        String targetConstraint = "uq_wiki_saved_articles_user_article";
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
