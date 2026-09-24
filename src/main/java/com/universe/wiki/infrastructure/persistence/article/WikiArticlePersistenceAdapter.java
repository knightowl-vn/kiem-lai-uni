package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.infrastructure.persistence.image.WikiImageReferenceSynchronizer;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WikiArticlePersistenceAdapter
        implements WikiArticleRepositoryPort {

    private final SpringDataWikiArticleJpaRepository
            repository;
    private final WikiImageReferenceSynchronizer
    imageReferenceSynchronizer;
    
    public WikiArticlePersistenceAdapter(
            SpringDataWikiArticleJpaRepository repository,
            WikiImageReferenceSynchronizer imageReferenceSynchronizer
    ) {
        this.repository =
                repository;

        this.imageReferenceSynchronizer =
                imageReferenceSynchronizer;
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
        
        imageReferenceSynchronizer
        .syncArticleReferences(
                article.getId(),
                article.getContent()
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

    @Override
    public boolean hasCoverReference(
            UUID mediaAssetId
    ) {
        if (mediaAssetId == null) {
            return false;
        }

        List<String> referenceIds = repository.findCoverReferenceIdsCurrentRead(
                mediaAssetId.toString()
        );
        return !referenceIds.isEmpty();
    }

    @Override
    public void lockCoverReferenceKey(
            UUID mediaAssetId
    ) {
        if (mediaAssetId == null) {
            return;
        }

        String assetIdStr = mediaAssetId.toString();
        List<String> existing = repository.findCoverReferenceIds(assetIdStr);
        if (!existing.isEmpty()) {
            repository.lockArticleIds(existing);
        }
    }

    @Override
    public void flush() {
        repository.flush();
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

        entity.setCoverMediaAssetId(
                toNullableString(
                        article.getCoverMediaAssetId()
                )
        );

        entity.setCoverPositionX(
                toPersistenceByte(
                        article.getCoverPositionX(),
                        "coverPositionX"
                )
        );

        entity.setCoverPositionY(
                toPersistenceByte(
                        article.getCoverPositionY(),
                        "coverPositionY"
                )
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
                toNullableUuid(
                        entity.getCoverMediaAssetId()
                ),
                toDomainInt(
                        entity.getCoverPositionX()
                ),
                toDomainInt(
                        entity.getCoverPositionY()
                ),
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

    private byte toPersistenceByte(
            int value,
            String fieldName
    ) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(
                    fieldName + " phải nằm trong khoảng 0 đến 100: " + value
            );
        }
        return (byte) value;
    }

    private int toDomainInt(
            byte value
    ) {
        int intVal = Byte.toUnsignedInt(value);
        if (intVal < 0 || intVal > 100) {
            throw new IllegalStateException(
                    "Dữ liệu cover focal position không hợp lệ từ persistence: " + intVal
            );
        }
        return intVal;
    }
}