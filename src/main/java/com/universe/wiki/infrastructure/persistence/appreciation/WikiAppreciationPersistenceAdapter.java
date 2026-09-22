package com.universe.wiki.infrastructure.persistence.appreciation;

import com.universe.wiki.application.exceptions.DuplicateWikiAppreciationException;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter persistence thực thi WikiAppreciationRepositoryPort.
 *
 * Chịu trách nhiệm:
 * 1. Chuyển đổi hai chiều giữa domain aggregate WikiAppreciationRating và JPA Entity;
 * 2. Lưu mới hoặc cập nhật bản ghi đánh giá vào MySQL;
 * 3. Tra cứu bản ghi đánh giá hiện hành theo wikiArticleId và userId.
 */
@Component
public class WikiAppreciationPersistenceAdapter implements WikiAppreciationRepositoryPort {

    private final SpringDataWikiAppreciationJpaRepository repository;

    public WikiAppreciationPersistenceAdapter(
            SpringDataWikiAppreciationJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataWikiAppreciationJpaRepository không được để trống."
        );
    }

    @Override
    public Optional<WikiAppreciationRating> findByWikiArticleIdAndUserId(UUID wikiArticleId, UUID userId) {
        if (wikiArticleId == null || userId == null) {
            return Optional.empty();
        }
        return repository.findByWikiArticleIdAndUserId(
                wikiArticleId.toString(),
                userId.toString()
        ).map(this::toDomain);
    }

    @Override
    @Transactional
    public WikiAppreciationRating save(WikiAppreciationRating rating) {
        if (rating == null) {
            throw new IllegalArgumentException("WikiAppreciationRating không được để trống.");
        }

        String id = rating.getId().toString();
        WikiAppreciationJpaEntity entity = repository.findById(id)
                .orElseGet(WikiAppreciationJpaEntity::new);

        mapToEntity(rating, entity);
        try {
            WikiAppreciationJpaEntity saved = repository.saveAndFlush(entity);
            return toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateRatingConstraintViolation(ex)) {
                throw new DuplicateWikiAppreciationException(
                        rating.getWikiArticleId(),
                        rating.getUserId(),
                        ex
                );
            }
            throw ex;
        }
    }

    private static final String TARGET_UNIQUE_CONSTRAINT = "uq_wiki_appreciation_ratings_article_user";

    private boolean isDuplicateRatingConstraintViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null
                        && cve.getConstraintName().toLowerCase().contains(TARGET_UNIQUE_CONSTRAINT)) {
                    return true;
                }
            }
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(TARGET_UNIQUE_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private WikiAppreciationRating toDomain(WikiAppreciationJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return WikiAppreciationRating.rehydrate(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getWikiArticleId()),
                UUID.fromString(entity.getUserId()),
                entity.getValue(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private void mapToEntity(WikiAppreciationRating domain, WikiAppreciationJpaEntity entity) {
        entity.setId(domain.getId().toString());
        entity.setWikiArticleId(domain.getWikiArticleId().toString());
        entity.setUserId(domain.getUserId().toString());
        entity.setValue(domain.getValue());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
    }
}
