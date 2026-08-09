package com.universe.wiki.application.article.query.published;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ListPublishedWikiArticlesUseCase {

    private static final int MAX_PAGE_SIZE =
            100;

    private final WikiArticleQueryPort
            articleQueryPort;

    public ListPublishedWikiArticlesUseCase(
            WikiArticleQueryPort articleQueryPort
    ) {
        this.articleQueryPort =
                articleQueryPort;
    }

    @Transactional(readOnly = true)
    public PublishedWikiArticlePageDTO execute(
            ListPublishedWikiArticlesQuery query
    ) {
        Objects.requireNonNull(
                query,
                "List published wiki articles query "
                        + "không được để trống."
        );

        validatePagination(
                query.page(),
                query.size()
        );

        String normalizedKeyword =
                normalizeKeyword(
                        query.keyword()
                );

        return articleQueryPort.findPublishedPage(
                normalizedKeyword,
                query.articleType(),
                query.page(),
                query.size()
        );
    }

    private void validatePagination(
            int page,
            int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "Page không được nhỏ hơn 0."
            );
        }

        if (size < 1) {
            throw new IllegalArgumentException(
                    "Page size phải lớn hơn hoặc bằng 1."
            );
        }

        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size không được vượt quá "
                            + MAX_PAGE_SIZE
                            + "."
            );
        }
    }

    private String normalizeKeyword(
            String keyword
    ) {
        if (keyword == null
                || keyword.isBlank()) {

            return null;
        }

        return keyword.trim();
    }
}