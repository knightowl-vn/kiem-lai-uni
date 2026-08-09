package com.universe.wiki.application.article.query.detail;

import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetWikiArticleDetailUseCase {

    private final WikiArticleQueryPort
            articleQueryPort;

    public GetWikiArticleDetailUseCase(
            WikiArticleQueryPort articleQueryPort
    ) {
        this.articleQueryPort =
                articleQueryPort;
    }

    @Transactional(readOnly = true)
    public WikiArticleDTO execute(
            GetWikiArticleDetailQuery query
    ) {
        Objects.requireNonNull(
                query,
                "Get wiki article detail query không được để trống."
        );

        UUID articleId =
                Objects.requireNonNull(
                        query.articleId(),
                        "Article ID không được để trống."
                );

        return articleQueryPort
                .findDetailById(articleId)
                .orElseThrow(() ->
                        new WikiArticleNotFoundException(
                                articleId
                        )
                );
    }
}