package com.universe.wiki.application.article.query.list;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticlePageDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ListWikiArticlesUseCase {

    private static final int MAX_PAGE_SIZE =
            100;

    private final WikiArticleQueryPort
            articleQueryPort;

    public ListWikiArticlesUseCase(
            WikiArticleQueryPort articleQueryPort
    ) {
        this.articleQueryPort =
                articleQueryPort;
    }

    @Transactional(readOnly = true)
    public WikiArticlePageDTO execute(
            ListWikiArticlesQuery query
    ) {
        Objects.requireNonNull(
                query,
                "List wiki articles query không được để trống."
        );

        validatePagination(
                query.page(),
                query.size()
        );

        String normalizedKeyword =
                normalizeKeyword(
                        query.keyword()
                );

        return articleQueryPort.findPage(
                normalizedKeyword,
                query.articleType(),
                query.status(),
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