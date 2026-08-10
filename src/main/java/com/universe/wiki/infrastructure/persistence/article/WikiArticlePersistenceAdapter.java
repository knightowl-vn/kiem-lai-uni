package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WikiArticlePersistenceAdapter
        implements WikiArticleRepositoryPort {

    private final SpringDataWikiArticleJpaRepository
            repository;

    public WikiArticlePersistenceAdapter(
            SpringDataWikiArticleJpaRepository repository
    ) {
        this.repository =
                repository;
    }

    @Override
    public Optional<WikiArticle> findById(
            UUID articleId
    ) {
        if (articleId == null) {
            return Optional.empty();
        }

        return repository
                .findById(
                        articleId.toString()
                )
                .map(this::toDomain);
    }

    @Override
    public Optional<WikiArticle>
            findByArticleTypeAndSlug(
                    ArticleType articleType,
                    Slug slug
            ) {

        if (articleType == null
                || slug == null) {

            return Optional.empty();
        }

        return repository
                .findByArticleTypeAndSlug(
                        articleType.name(),
                        slug.value()
                )
                .map(this::toDomain);
    }

    @Override
    public boolean existsByArticleTypeAndSlug(
            ArticleType articleType,
            Slug slug
    ) {
        if (articleType == null
                || slug == null) {

            return false;
        }

        return repository
                .existsByArticleTypeAndSlug(
                        articleType.name(),
                        slug.value()
                );
    }

    @Override
    public void save(
            WikiArticle article
    ) {
        if (article == null) {
            throw new IllegalArgumentException(
                    "Wiki article không được để trống."
            );
        }

        String articleId =
                article.getId().toString();

        /*
         * Với bản ghi mới, tạo entity mới.
         *
         * Với bản ghi đã tồn tại, lấy lại entity đang có
         * để giữ nguyên persistenceVersion do Hibernate quản lý.
         */
        WikiArticleJpaEntity entity =
                repository.findById(articleId)
                        .orElseGet(
                                WikiArticleJpaEntity::new
                        );

        mapToEntity(
                article,
                entity
        );

        repository.save(
                entity
        );
    }
    
    @Override
    public void deleteById(
            UUID articleId
    ) {
        if (articleId == null) {
            throw new IllegalArgumentException(
                    "Article ID không được để trống."
            );
        }

        repository.deleteById(
                articleId.toString()
        );
    }

    private void mapToEntity(
            WikiArticle article,
            WikiArticleJpaEntity entity
    ) {
        entity.setId(
                article.getId().toString()
        );

        entity.setTitle(
                article.getTitle()
        );

        entity.setSlug(
                article.getSlug().value()
        );

        entity.setArticleType(
                article.getArticleType().name()
        );

        entity.setSummary(
                article.getSummary()
        );

        entity.setContent(
                article.getContent()
        );

        entity.setStatus(
                article.getStatus().name()
        );

        entity.setCreatedBy(
                article.getCreatedBy().toString()
        );

        entity.setUpdatedBy(
                toNullableString(
                        article.getUpdatedBy()
                )
        );

        entity.setPublishedBy(
                toNullableString(
                        article.getPublishedBy()
                )
        );

        entity.setArchivedBy(
                toNullableString(
                        article.getArchivedBy()
                )
        );

        entity.setAggregateVersion(
                article.getAggregateVersion()
        );
        
        entity.setContentVersion(
                article.getContentVersion()
        );

        entity.setCreatedAt(
                article.getCreatedAt()
        );

        entity.setUpdatedAt(
                article.getUpdatedAt()
        );

        entity.setPublishedAt(
                article.getPublishedAt()
        );

        entity.setArchivedAt(
                article.getArchivedAt()
        );
    }

    private WikiArticle toDomain(
            WikiArticleJpaEntity entity
    ) {
        return WikiArticle.rehydrate(
                UUID.fromString(
                        entity.getId()
                ),
                entity.getTitle(),
                new Slug(
                        entity.getSlug()
                ),
                ArticleType.valueOf(
                        entity.getArticleType()
                ),
                entity.getSummary(),
                entity.getContent(),
                ArticleStatus.valueOf(
                        entity.getStatus()
                ),
                UUID.fromString(
                        entity.getCreatedBy()
                ),
                toNullableUuid(
                        entity.getUpdatedBy()
                ),
                toNullableUuid(
                        entity.getPublishedBy()
                ),
                toNullableUuid(
                        entity.getArchivedBy()
                ),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPublishedAt(),
                entity.getArchivedAt(),
                entity.getAggregateVersion(),
                entity.getContentVersion()
        );
    }

    private String toNullableString(
            UUID value
    ) {
        return value == null
                ? null
                : value.toString();
    }

    private UUID toNullableUuid(
            String value
    ) {
        return value == null
                || value.isBlank()
                ? null
                : UUID.fromString(value);
    }
}