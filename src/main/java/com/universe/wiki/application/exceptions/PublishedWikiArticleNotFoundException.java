package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;
import com.universe.wiki.domain.article.ArticleType;

public class PublishedWikiArticleNotFoundException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public PublishedWikiArticleNotFoundException(
            ArticleType articleType,
            String slug
    ) {
        super(
                "WIKI_PUBLISHED_ARTICLE_NOT_FOUND",
                "Không tìm thấy bài Wiki đã xuất bản thuộc loại "
                        + articleType.name()
                        + " với slug: "
                        + slug
        );
    }
}