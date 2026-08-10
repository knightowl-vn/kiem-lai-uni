package com.universe.wiki.infrastructure.persistence.revision;

import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;

import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;

import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WikiArticleRevisionPersistenceAdapter
        implements WikiArticleRevisionRepositoryPort {

    private final SpringDataWikiArticleRevisionJpaRepository
            repository;


    public WikiArticleRevisionPersistenceAdapter(
            SpringDataWikiArticleRevisionJpaRepository repository
    ) {
        this.repository =
                repository;
    }


    /*
     * =====================================================
     * SAVE
     * =====================================================
     */

    @Override
    public void save(
            WikiArticleRevision revision
    ) {
        if (revision == null) {
            throw new IllegalArgumentException(
                    "Wiki article revision không được để trống."
            );
        }

        WikiArticleRevisionJpaEntity entity =
                toEntity(
                        revision
                );

        repository.save(
                entity
        );
    }


    /*
     * =====================================================
     * DELETE
     * =====================================================
     */

    @Override
    public void deleteAllByArticleId(
            UUID articleId
    ) {
        if (articleId == null) {
            throw new IllegalArgumentException(
                    "Article ID không được để trống."
            );
        }

        repository.deleteAllByArticleId(
                articleId.toString()
        );
    }


    /*
     * =====================================================
     * FIND
     * =====================================================
     */

    @Override
    public Optional<WikiArticleRevision>
            findByArticleIdAndRevisionNumber(
                    UUID articleId,
                    long revisionNumber
            ) {

        if (
                articleId == null
                || revisionNumber < 1L
        ) {
            return Optional.empty();
        }

        return repository
                .findByArticleIdAndRevisionNumber(
                        articleId.toString(),
                        revisionNumber
                )
                .map(
                        this::toDomain
                );
    }


    /*
     * =====================================================
     * DOMAIN → JPA
     * =====================================================
     */

    private WikiArticleRevisionJpaEntity toEntity(
            WikiArticleRevision revision
    ) {
        WikiArticleRevisionJpaEntity entity =
                new WikiArticleRevisionJpaEntity();

        entity.setId(
                revision
                        .id()
                        .toString()
        );

        entity.setArticleId(
                revision
                        .articleId()
                        .toString()
        );

        entity.setRevisionNumber(
                revision.revisionNumber()
        );

        /*
         * Phiên bản nội dung tại thời điểm snapshot.
         */
        entity.setContentVersion(
                revision.contentVersion()
        );

        entity.setTitle(
                revision.title()
        );

        entity.setSlug(
                revision
                        .slug()
                        .value()
        );

        entity.setArticleType(
                revision
                        .articleType()
                        .name()
        );

        entity.setSummary(
                revision.summary()
        );

        entity.setContent(
                revision.content()
        );

        entity.setStatus(
                revision
                        .status()
                        .name()
        );

        entity.setChangeType(
                revision
                        .changeType()
                        .name()
        );

        entity.setEditSummary(
                revision.editSummary()
        );

        entity.setEditedBy(
                revision
                        .editedBy()
                        .toString()
        );

        entity.setCreatedAt(
                revision.createdAt()
        );

        return entity;
    }


    /*
     * =====================================================
     * JPA → DOMAIN
     * =====================================================
     */

    private WikiArticleRevision toDomain(
            WikiArticleRevisionJpaEntity entity
    ) {
        return new WikiArticleRevision(
                UUID.fromString(
                        entity.getId()
                ),

                UUID.fromString(
                        entity.getArticleId()
                ),

                entity.getRevisionNumber(),

                entity.getContentVersion(),

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

                RevisionChangeType.valueOf(
                        entity.getChangeType()
                ),

                entity.getEditSummary(),

                UUID.fromString(
                        entity.getEditedBy()
                ),

                entity.getCreatedAt()
        );
    }
}