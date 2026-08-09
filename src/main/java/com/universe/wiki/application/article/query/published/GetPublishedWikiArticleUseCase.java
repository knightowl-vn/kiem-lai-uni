package com.universe.wiki.application.article.query.published;

import com.universe.wiki.application.exceptions
        .PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports
        .WikiArticleQueryPort;
import com.universe.wiki.contracts.dto
        .PublishedWikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class GetPublishedWikiArticleUseCase {

    private final WikiArticleQueryPort
            articleQueryPort;

    public GetPublishedWikiArticleUseCase(
            WikiArticleQueryPort articleQueryPort
    ) {
        this.articleQueryPort =
                articleQueryPort;
    }

    @Transactional(readOnly = true)
    public PublishedWikiArticleDTO execute(
            GetPublishedWikiArticleQuery query
    ) {
        Objects.requireNonNull(
                query,
                "Get published wiki article query "
                        + "không được để trống."
        );

        ArticleType articleType =
                Objects.requireNonNull(
                        query.articleType(),
                        "Article type không được để trống."
                );

        Slug slug =
                new Slug(
                        query.slug()
                );

        return articleQueryPort
                .findPublishedByArticleTypeAndSlug(
                        articleType,
                        slug
                )
                .orElseThrow(() ->
                        new PublishedWikiArticleNotFoundException(
                                articleType,
                                slug.value()
                        )
                );
    }
}