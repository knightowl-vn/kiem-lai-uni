package com.universe.wiki.infrastructure.persistence.image;

import com.universe.wiki.application.image
        .WikiImageReferenceBackfillResult;

import com.universe.wiki.application.ports
        .WikiImageReferenceBackfillPort;

import com.universe.wiki.infrastructure.persistence.article
        .SpringDataWikiArticleJpaRepository;
import com.universe.wiki.infrastructure.persistence.article
        .WikiArticleJpaEntity;

import com.universe.wiki.infrastructure.persistence.revision
        .SpringDataWikiArticleRevisionJpaRepository;
import com.universe.wiki.infrastructure.persistence.revision
        .WikiArticleRevisionJpaEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WikiImageReferenceBackfillPersistenceAdapter
        implements WikiImageReferenceBackfillPort {

    private static final int BATCH_SIZE =
            100;

    private final SpringDataWikiArticleJpaRepository
            articleRepository;

    private final SpringDataWikiArticleRevisionJpaRepository
            revisionRepository;

    private final WikiImageReferenceSynchronizer
            referenceSynchronizer;


    public WikiImageReferenceBackfillPersistenceAdapter(
            SpringDataWikiArticleJpaRepository articleRepository,
            SpringDataWikiArticleRevisionJpaRepository revisionRepository,
            WikiImageReferenceSynchronizer referenceSynchronizer
    ) {
        this.articleRepository =
                articleRepository;

        this.revisionRepository =
                revisionRepository;

        this.referenceSynchronizer =
                referenceSynchronizer;
    }


    @Override
    public WikiImageReferenceBackfillResult backfill() {

        long articlesScanned =
                backfillArticles();

        long revisionsScanned =
                backfillRevisions();

        return new WikiImageReferenceBackfillResult(
                articlesScanned,
                revisionsScanned
        );
    }


    /*
     * =====================================================
     * ARTICLES
     * =====================================================
     */

    private long backfillArticles() {

        long scanned = 0L;

        int pageNumber = 0;

        Page<WikiArticleJpaEntity>
                page;

        do {

            page =
                    articleRepository.findAll(
                            PageRequest.of(
                                    pageNumber,
                                    BATCH_SIZE,
                                    Sort.by(
                                            Sort.Direction.ASC,
                                            "id"
                                    )
                            )
                    );


            for (
                    WikiArticleJpaEntity article :
                    page.getContent()
            ) {

                referenceSynchronizer
                        .syncArticleReferences(
                                UUID.fromString(
                                        article.getId()
                                ),
                                article.getContent()
                        );

                scanned++;
            }


            pageNumber++;

        } while (
                page.hasNext()
        );

        return scanned;
    }


    /*
     * =====================================================
     * REVISIONS
     * =====================================================
     */

    private long backfillRevisions() {

        long scanned = 0L;

        int pageNumber = 0;

        Page<WikiArticleRevisionJpaEntity>
                page;

        do {

            page =
                    revisionRepository.findAll(
                            PageRequest.of(
                                    pageNumber,
                                    BATCH_SIZE,
                                    Sort.by(
                                            Sort.Direction.ASC,
                                            "id"
                                    )
                            )
                    );


            for (
                    WikiArticleRevisionJpaEntity revision :
                    page.getContent()
            ) {

                referenceSynchronizer
                        .syncRevisionReferences(
                                UUID.fromString(
                                        revision.getId()
                                ),
                                revision.getContent()
                        );

                scanned++;
            }


            pageNumber++;

        } while (
                page.hasNext()
        );

        return scanned;
    }
}